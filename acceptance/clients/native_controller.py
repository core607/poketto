"""Bounded observation of one fixture controller; never starts or retries another attempt."""
import json
import os
import selectors
import time


class ControllerOutput:
    def __init__(self, process, output):
        self.process, self.output = process, output
        self.pending = bytearray()
        self.receipts = {}
        self.ended = False
        self.selector = selectors.DefaultSelector()
        self.selector.register(process.stdout, selectors.EVENT_READ)

    def close(self):
        self.selector.close()

    def line(self, deadline):
        while b'\n' not in self.pending:
            if self.ended:
                if not self.pending:
                    return None
                value, self.pending = bytes(self.pending), bytearray()
                return value.decode('utf-8')
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError('The original native controller exceeded its observation deadline')
            if not self.selector.select(remaining):
                continue
            value = os.read(self.process.stdout.fileno(), 8192)
            self.output.write(value)
            self.output.flush()
            if not value:
                self.ended = True
            self.pending.extend(value)
            if len(self.pending) > 1024 * 1024:
                raise ValueError('Native controller output exceeded its line bound')
        value, rest = self.pending.split(b'\n', 1)
        self.pending = bytearray(rest)
        return value.decode('utf-8')

    def until(self, field, seconds):
        if field in self.receipts:
            return self.receipts.pop(field)
        deadline = time.monotonic() + seconds
        while (line := self.line(deadline)) is not None:
            print(line, flush=True)
            if line.startswith('{'):
                message = json.loads(line)
                if field in message:
                    return message
                for name in ('ready', 'cleanup'):
                    if name in message:
                        self.receipts[name] = message
        raise RuntimeError('The native controller ended without ' + field)
