"""Per-lease FIFO requests and protected replies; no socket creation inside SRT."""
import json
import os
from pathlib import Path
import select
import struct
import threading
import time
import uuid

MAX_FRAME = 512 * 1024
MAX_PENDING = 4
MAX_REQUESTS = 256
OPERATIONS = frozenset(('status', 'save', 'recover', 'sync', 'media_fetch', 'media_import', 'move', 'export'))

class BridgeRejected(Exception):
    pass


def _object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise BridgeRejected('Duplicate request key')
        result[key] = value
    return result


def encode(value):
    result = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode('utf-8')
    if not 0 < len(result) <= MAX_FRAME:
        raise BridgeRejected('Bridge frame exceeds its bound')
    return result


def identifier(value):
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, AttributeError, TypeError):
        raise BridgeRejected('Invalid request identity') from None
    return value


class LeaseBridge:
    """The supervisor owns this directory outside mutable work; SRT exposes only
    its request FIFO, advisory lock and read-only response directory to this lease.
    UID/GID filesystem isolation and native sandbox mounts are required. A client
    identifier correlates requests only; it never supplies principal or scope.
    """
    def __init__(self, path, gid):
        self.path = Path(path)
        self.gid = gid
        self.condition = threading.RLock()
        self.reader = threading.Lock()
        self.closer = threading.Lock()
        self.pending = {}
        self.seen = set()
        self.closed = False
        self.buffer = bytearray()
        self.frame_started = None
        self.path.mkdir(mode=0o750)
        os.chown(self.path, -1, gid)
        os.chmod(self.path, 0o750)
        self.responses = self.path / 'responses'
        self.responses.mkdir(mode=0o750)
        os.chown(self.responses, -1, gid)
        os.chmod(self.responses, 0o750)
        os.mkfifo(self.path / 'requests', 0o620)
        os.chown(self.path / 'requests', -1, gid)
        os.chmod(self.path / 'requests', 0o620)
        lock = os.open(self.path / 'lock', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o440)
        os.close(lock)
        os.chown(self.path / 'lock', -1, gid)
        os.chmod(self.path / 'lock', 0o440)
        state = os.open(self.path / 'state', os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o640)
        os.close(state)
        os.chown(self.path / 'state', -1, gid)
        os.chmod(self.path / 'state', 0o640)
        self.input = os.open(self.path / 'requests', os.O_RDWR | os.O_NONBLOCK | os.O_NOFOLLOW)

    def _message(self, value):
        if not isinstance(value, dict) or not isinstance(value.get('operation'), str):
            raise BridgeRejected('Invalid bridge request')
        request_id = identifier(value.get('requestId'))
        with self.condition:
            if self.closed:
                raise BridgeRejected('Session closed')
            if value['operation'] == 'ack':
                if set(value) != {'operation', 'requestId'}:
                    raise BridgeRejected('Invalid acknowledgement')
                item = self.pending.get(request_id)
                if item is not None and item['completed']:
                    (self.responses / (request_id + '.json')).unlink(missing_ok=True)
                    self.pending.pop(request_id)
                return None
            if set(value) != {'requestId', 'operation', 'arguments'} or value['operation'] not in OPERATIONS or not isinstance(value['arguments'], dict):
                raise BridgeRejected('Invalid bridge request')
            if request_id in self.seen or len(self.seen) >= MAX_REQUESTS or len(self.pending) >= MAX_PENDING:
                raise BridgeRejected('Bridge request capacity or replay limit')
            self.seen.add(request_id)
            self.pending[request_id] = {'completed': False}
            return value

    def poll(self, timeout=0.2):
        if not self.reader.acquire(blocking=False):
            raise BridgeRejected('Bridge poll already active')
        deadline = time.monotonic() + min(max(timeout, 0), 2)
        try:
            while True:
                if self.closed:
                    raise BridgeRejected('Session closed')
                now = time.monotonic()
                if self.frame_started is not None and now - self.frame_started > 5:
                    raise BridgeRejected('Bridge frame deadline exceeded')
                if len(self.buffer) >= 4:
                    size, = struct.unpack('!I', self.buffer[:4])
                    if not 0 < size <= MAX_FRAME:
                        raise BridgeRejected('Invalid bridge frame')
                    if len(self.buffer) >= size + 4:
                        raw = bytes(self.buffer[4:4 + size])
                        del self.buffer[:4 + size]
                        self.frame_started = now if self.buffer else None
                        value = json.loads(raw, object_pairs_hook=_object,
                            parse_constant=lambda _: (_ for _ in ()).throw(BridgeRejected('Invalid JSON value')))
                        request = self._message(value)
                        if request is not None:
                            return request
                        continue
                remaining = deadline - now
                if remaining <= 0:
                    return None
                ready, _, _ = select.select([self.input], [], [], min(remaining, 0.05))
                if ready:
                    if self.frame_started is None:
                        self.frame_started = time.monotonic()
                    self.buffer.extend(os.read(self.input, min(65536, MAX_FRAME + 4 - len(self.buffer))))
        except (OSError, ValueError, RecursionError) as error:
            raise BridgeRejected('Invalid or closed bridge input') from error
        finally:
            self.reader.release()

    def complete(self, request_id, response):
        identifier(request_id)
        encoded = encode(response)
        with self.condition:
            item = self.pending.get(request_id)
            if self.closed or item is None or item['completed']:
                raise BridgeRejected('No matching pending request')
            temporary = self.responses / (request_id + '.pending')
            destination = self.responses / (request_id + '.json')
            try:
                fd = os.open(temporary, os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o440)
                with os.fdopen(fd, 'wb') as output:
                    os.fchown(output.fileno(), -1, self.gid)
                    os.fchmod(output.fileno(), 0o440)
                    output.write(encoded)
                os.rename(temporary, destination)
                item['completed'] = True
            finally:
                temporary.unlink(missing_ok=True)

    def close(self):
        with self.closer:
            if self.closed:
                return
            self.closed = True
            with self.reader:
                os.close(self.input)
                self.buffer.clear()
            with self.condition:
                for request_id in self.pending:
                    (self.responses / (request_id + '.json')).unlink(missing_ok=True)
                self.pending.clear()
            # Update the existing inode: native SRT exposes this file through a read-only bind.
            (self.path / 'state').write_bytes(b'closed\n')

    def reset_command(self):
        """Call after the command cgroup is empty; abandoned requests cannot enter a later command."""
        with self.reader:
            with self.condition:
                if self.closed:
                    return
                for request_id in self.pending:
                    (self.responses / (request_id + '.json')).unlink(missing_ok=True)
                self.pending.clear()
                self.buffer.clear()
                self.frame_started = None
                while True:
                    try:
                        if not os.read(self.input, 65536):
                            break
                    except BlockingIOError:
                        break
