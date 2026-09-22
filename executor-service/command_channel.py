"""Bounded supervisor-side transport for one persistent sandbox shell."""
import base64
import json
import os
import select
import selectors
import time

from artifacts import MAX_OUTPUT_BYTES

MAX_FRAME = 65536


class CommandEnded(Exception):
    def __init__(self, reason, output, truncated):
        self.reason = reason
        self.output = output
        self.truncated = truncated
        super().__init__(reason)


class CommandChannel:
    def __init__(self, process):
        self.process = process
        self.selector = selectors.DefaultSelector()
        self.selector.register(process.stdout, selectors.EVENT_READ, 'frame')
        self.selector.register(process.stderr, selectors.EVENT_READ, 'diagnostic')
        os.set_blocking(process.stdin.fileno(), False)
        self.buffer = bytearray()
        self.ready = False

    def close(self):
        self.selector.close()
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()

    def events(self, wait):
        for key, _ in self.selector.select(wait):
            chunk = os.read(key.fileobj.fileno(), 8192)
            if not chunk:
                self.selector.unregister(key.fileobj)
                continue
            if key.data == 'diagnostic':
                yield 'diagnostic', chunk
                continue
            self.buffer.extend(chunk)
            while b'\n' in self.buffer:
                end = self.buffer.index(b'\n')
                if end > MAX_FRAME:
                    raise ValueError('Sandbox output frame exceeds its bound')
                raw = bytes(self.buffer[:end])
                del self.buffer[:end + 1]
                yield 'frame', json.loads(raw)
            if len(self.buffer) > MAX_FRAME:
                raise ValueError('Sandbox output frame exceeds its bound')

    def execute(self, execution_id, command, timeout_ms, cancelled, cancellation_reason):
        output = [bytearray(), bytearray()]
        truncated = [False, False]
        deadline = time.monotonic() + timeout_ms / 1000

        def check():
            if cancelled():
                raise CommandEnded(cancellation_reason(), output, truncated)
            if time.monotonic() >= deadline:
                raise CommandEnded('timeout', output, truncated)

        def append(index, data):
            available = MAX_OUTPUT_BYTES - sum(len(stream) for stream in output)
            output[index].extend(data[:available])
            if len(data) > available:
                truncated[index] = True
                raise CommandEnded('output_limit', output, truncated)

        def events():
            check()
            received = list(self.events(min(0.05, max(0, deadline - time.monotonic()))))
            if not received and not self.selector.get_map():
                raise CommandEnded('sandbox_failed', output, truncated)
            return received

        try:
            while not self.ready:
                for kind, value in events():
                    if kind == 'diagnostic':
                        append(1, value)
                    elif value == {'kind': 'ready'}:
                        self.ready = True
                    else:
                        raise ValueError('Unexpected sandbox greeting')
            frame = memoryview(json.dumps(
                {'executionId': execution_id, 'command': command},
                ensure_ascii=False, separators=(',', ':')).encode() + b'\n')
            while frame:
                check()
                _, writable, _ = select.select([], [self.process.stdin], [], 0.05)
                if writable:
                    try:
                        frame = frame[os.write(self.process.stdin.fileno(), frame):]
                    except BlockingIOError:
                        pass
            while True:
                for kind, value in events():
                    if kind == 'diagnostic':
                        append(1, value)
                        continue
                    if not isinstance(value, dict) or value.get('executionId') != execution_id:
                        raise ValueError('Sandbox command identity mismatch')
                    if value.get('kind') == 'output':
                        if set(value) != {'kind', 'executionId', 'stream', 'data'} or value['stream'] not in ('stdout', 'stderr'):
                            raise ValueError('Invalid sandbox output')
                        data = base64.b64decode(value['data'], validate=True)
                        if len(data) > 8192:
                            raise ValueError('Sandbox output chunk exceeds its bound')
                        append(0 if value['stream'] == 'stdout' else 1, data)
                    elif value.get('kind') == 'complete':
                        if (set(value) != {'kind', 'executionId', 'exitCode', 'shellExited'}
                                or type(value['exitCode']) is not int or not 0 <= value['exitCode'] <= 255
                                or type(value['shellExited']) is not bool):
                            raise ValueError('Invalid sandbox completion')
                        return value, output, truncated
                    else:
                        raise ValueError('Unexpected sandbox frame')
        except (OSError, ValueError, TypeError, RecursionError) as error:
            raise CommandEnded('sandbox_failed', output, truncated) from error
