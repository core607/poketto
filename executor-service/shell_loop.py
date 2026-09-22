#!/usr/bin/env python3
"""Fixed SRT-side shell driver. Its frames report execution, never grant host authority."""
import base64
import array
import fcntl
import json
import os
from pathlib import Path
import selectors
import shlex
import subprocess
import sys
import tempfile
import termios
import uuid

MAX_COMMAND = 65536
MAX_INPUT = 6 * MAX_COMMAND + 1024
CHUNK = 8192
MAX_RETAINED_STREAMS = 128


def emit(kind, **fields):
    data = json.dumps({'kind': kind, **fields}, separators=(',', ':')).encode() + b'\n'
    sys.stdout.buffer.write(data)
    sys.stdout.buffer.flush()


class ShellLoop:
    def __init__(self, repository):
        self.directory = tempfile.TemporaryDirectory(prefix='poketto-command-')
        self.selector = selectors.DefaultSelector()
        self.streams = {}
        self.active = None
        self.requests = bytearray()
        self.replies = bytearray()
        self.reader, writer = os.pipe()
        self.shell = subprocess.Popen(
            ['/bin/bash', '--noprofile', '--norc'], stdin=subprocess.PIPE,
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, pass_fds=(writer,),
            cwd=repository)
        self.reply_fd = writer
        os.close(writer)
        os.set_blocking(self.reader, False)
        self.selector.register(self.reader, selectors.EVENT_READ, 'reply')
        os.set_blocking(sys.stdin.fileno(), False)
        self.selector.register(sys.stdin.fileno(), selectors.EVENT_READ, 'request')
        self.shell.stdin.write(b'shopt -s expand_aliases\n')
        self.shell.stdin.flush()

    def begin(self, raw):
        request = json.loads(raw)
        if set(request) != {'executionId', 'command'} or self.active is not None:
            raise ValueError('Unexpected command frame')
        identity, command = request['executionId'], request['command']
        if not isinstance(identity, str) or str(uuid.UUID(identity)) != identity:
            raise ValueError('Invalid command identity')
        if not isinstance(command, str) or '\0' in command or not 0 < len(command.encode()) <= MAX_COMMAND:
            raise ValueError('Invalid command source')
        while len(self.streams) > MAX_RETAINED_STREAMS - 2:
            self.release_stream(next(iter(self.streams)))
        self.active = identity
        paths = []
        for stream in ('stdout', 'stderr'):
            path = Path(self.directory.name) / (identity + '.' + stream)
            os.mkfifo(path, 0o600)
            descriptor = os.open(path, os.O_RDONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
            # A sentinel prevents a pre-open EOF; the shell opens both writers before START.
            sentinel = os.open(path, os.O_WRONLY | os.O_NONBLOCK | os.O_NOFOLLOW)
            self.streams[descriptor] = (identity, stream, path, sentinel)
            self.selector.register(descriptor, selectors.EVENT_READ, 'output')
            paths.append(path)
        script = (
            '{ builtin printf "start ' + identity + '\\n" >&' + str(self.reply_fd) + '; '
            'export POKETTO_EXECUTION_ID=' + shlex.quote(identity) + '; '
            'builtin eval -- ' + shlex.quote(command) + '; '
            '_poketto_command_status=$?; '
            'builtin printf "done ' + identity + ' %s\\n" "$_poketto_command_status" >&' + str(self.reply_fd) + '; '
            '} >' + shlex.quote(str(paths[0])) + ' 2>' + shlex.quote(str(paths[1])) + ' </dev/null\n')
        self.shell.stdin.write(script.encode())
        self.shell.stdin.flush()

    def output(self, descriptor, limit=CHUNK):
        identity, stream, path, sentinel = self.streams[descriptor]
        try:
            data = os.read(descriptor, min(CHUNK, limit))
        except BlockingIOError:
            return 0
        if not data:
            self.release_stream(descriptor)
            return 0
        if identity == self.active:
            emit('output', executionId=identity, stream=stream, data=base64.b64encode(data).decode())
        # Completed-command writers keep their pipe; later bytes are drained without attribution.
        return len(data)

    def release_stream(self, descriptor):
        _, _, path, sentinel = self.streams.pop(descriptor)
        self.selector.unregister(descriptor)
        os.close(descriptor)
        if sentinel is not None:
            os.close(sentinel)
        path.unlink(missing_ok=True)

    def reply(self, raw):
        parts = raw.decode('ascii').split(' ')
        if len(parts) not in (2, 3) or parts[1] != self.active:
            raise ValueError('Invalid shell completion')
        if parts[0] == 'start' and len(parts) == 2:
            for descriptor, (identity, stream, path, sentinel) in list(self.streams.items()):
                if identity == self.active and sentinel is not None:
                    os.close(sentinel)
                    self.streams[descriptor] = (identity, stream, path, None)
            return
        if parts[0] != 'done' or len(parts) != 3 or not parts[2].isdigit() or not 0 <= int(parts[2]) <= 255:
            raise ValueError('Invalid shell exit status')
        self.complete(int(parts[2]), False)

    def complete(self, status, exited):
        if self.active is None:
            return
        # Read the finite bytes already in each FIFO at the boundary. Background writers may
        # continue indefinitely, so draining until EAGAIN is not a completion condition.
        for descriptor, (identity, _, _, _) in list(self.streams.items()):
            if identity == self.active:
                available = array.array('i', [0])
                fcntl.ioctl(descriptor, termios.FIONREAD, available, True)
                remaining = available[0]
                while remaining > 0:
                    received = self.output(descriptor, remaining)
                    if not received:
                        break
                    remaining -= received
        emit('complete', executionId=self.active, exitCode=status, shellExited=exited)
        self.active = None

    def run(self):
        emit('ready')
        while True:
            for key, _ in self.selector.select(0.05):
                if key.data == 'output':
                    if key.fd in self.streams:
                        self.output(key.fd)
                    continue
                data = os.read(key.fd, CHUNK)
                if not data:
                    if key.data == 'request':
                        return
                    self.selector.unregister(key.fd)
                    continue
                buffer = self.requests if key.data == 'request' else self.replies
                buffer.extend(data)
                if len(buffer) > (MAX_INPUT if key.data == 'request' else 1024):
                    raise ValueError('Shell channel frame exceeds its bound')
                while b'\n' in buffer:
                    end = buffer.index(b'\n')
                    raw = bytes(buffer[:end])
                    del buffer[:end + 1]
                    if key.data == 'request':
                        self.begin(raw)
                    else:
                        self.reply(raw)
            status = self.shell.poll()
            if status is not None:
                self.complete(status if status >= 0 else 128 - status, True)
                return

    def close(self):
        self.selector.close()
        if self.shell.poll() is None:
            self.shell.terminate()
            try:
                self.shell.wait(timeout=2)
            except subprocess.TimeoutExpired:
                self.shell.kill()
                self.shell.wait(timeout=2)
        self.shell.stdin.close()
        os.close(self.reader)
        for descriptor, (_, _, _, sentinel) in self.streams.items():
            os.close(descriptor)
            if sentinel is not None:
                os.close(sentinel)
        self.directory.cleanup()


def main():
    if len(sys.argv) != 2:
        raise SystemExit('Repository path required')
    loop = ShellLoop(sys.argv[1])
    try:
        loop.run()
    finally:
        loop.close()


if __name__ == '__main__':
    main()
