"""Per-lease command mailbox; the application alone authorizes and executes requests."""
import json
import os
from pathlib import Path
import socket
import struct
import threading
import time
import uuid

MAX_FRAME = 512 * 1024
MAX_PENDING = 4
OPERATIONS = frozenset(('status', 'save', 'media_fetch', 'media_import', 'move', 'export'))


class BridgeRejected(Exception):
    pass


def _read(connection, size, deadline):
    result = bytearray()
    while len(result) < size:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise BridgeRejected('Bridge deadline exceeded')
        connection.settimeout(remaining)
        chunk = connection.recv(size - len(result))
        if not chunk:
            raise BridgeRejected('Incomplete bridge frame')
        result.extend(chunk)
    return bytes(result)


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


class LeaseBridge:
    """Bind only below a supervisor-owned lease mount, never inside mutable work.

    The sandbox receives access to this socket alone, not the signed application
    control socket. Peer UID is an additional check; filesystem/sandbox isolation
    supplies the per-lease boundary when leases share the execution account.
    """
    def __init__(self, path, uid, gid):
        self.path = Path(path)
        self.uid = uid
        self.condition = threading.Condition()
        self.pending = {}
        self.connections = set()
        self.handlers = set()
        self.closed = False
        self.capacity = threading.BoundedSemaphore(MAX_PENDING)
        self.server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            self.server.bind(str(self.path))
            self.identity = self.path.lstat().st_ino
            os.chown(self.path, -1, gid)
            os.chmod(self.path, 0o660)
            self.server.listen(MAX_PENDING)
            self.server.settimeout(0.2)
        except BaseException:
            self.server.close()
            if hasattr(self, 'identity') and self.path.lstat().st_ino == self.identity:
                self.path.unlink()
            raise
        self.listener = threading.Thread(target=self._listen, daemon=True)
        self.listener.start()

    def _listen(self):
        while True:
            with self.condition:
                if self.closed:
                    return
            try:
                connection, _ = self.server.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            with self.condition:
                if self.closed or not self.capacity.acquire(blocking=False):
                    connection.close()
                    continue
                handler = threading.Thread(target=self._handle, args=(connection,), daemon=True)
                self.connections.add(connection)
                self.handlers.add(handler)
                handler.start()

    def _handle(self, connection):
        request_id = None
        try:
            _, uid, _ = struct.unpack('3i', connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if uid != self.uid:
                raise BridgeRejected('Unexpected command peer')
            deadline = time.monotonic() + 5
            size, = struct.unpack('!I', _read(connection, 4, deadline))
            if not 0 < size <= MAX_FRAME:
                raise BridgeRejected('Invalid bridge frame')
            value = json.loads(_read(connection, size, deadline), object_pairs_hook=_object,
                               parse_constant=lambda _: (_ for _ in ()).throw(BridgeRejected('Invalid JSON value')))
            if not isinstance(value, dict) or set(value) != {'operation', 'arguments'} or \
                    not isinstance(value['operation'], str) or value['operation'] not in OPERATIONS or \
                    not isinstance(value['arguments'], dict):
                raise BridgeRejected('Invalid bridge request')
            request_id = str(uuid.uuid4())
            item = {'request': value, 'dispatched': False, 'response': None}
            with self.condition:
                if self.closed:
                    return
                self.pending[request_id] = item
                self.condition.notify_all()
                completed = self.condition.wait_for(lambda: self.closed or item['response'] is not None, timeout=60)
                if not completed or self.closed:
                    return
                response = item['response']
            connection.settimeout(2)
            connection.sendall(struct.pack('!I', len(response)) + response)
        except (BridgeRejected, OSError, ValueError, RecursionError):
            pass
        finally:
            connection.close()
            self.capacity.release()
            with self.condition:
                if request_id is not None:
                    self.pending.pop(request_id, None)
                self.connections.discard(connection)
                self.handlers.discard(threading.current_thread())

    def poll(self, timeout=0.2):
        with self.condition:
            def available():
                return self.closed or any(not item['dispatched'] for item in self.pending.values())
            self.condition.wait_for(available, timeout=timeout)
            if self.closed:
                raise BridgeRejected('Session closed')
            for request_id, item in self.pending.items():
                if not item['dispatched']:
                    item['dispatched'] = True
                    return {'requestId': request_id, **item['request']}
            return None

    def complete(self, request_id, response):
        encoded = encode(response)
        with self.condition:
            item = self.pending.get(request_id)
            if self.closed or item is None or not item['dispatched'] or item['response'] is not None:
                raise BridgeRejected('No matching pending request')
            item['response'] = encoded
            self.condition.notify_all()

    def close(self):
        with self.condition:
            self.closed = True
            connections = list(self.connections)
            handlers = list(self.handlers)
            self.condition.notify_all()
        self.server.close()
        for connection in connections:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        deadline = time.monotonic() + 5
        for handler in [self.listener, *handlers]:
            handler.join(max(0, deadline - time.monotonic()))
            if handler.is_alive():
                raise BridgeRejected('Bridge did not stop')
        if self.path.exists() and self.path.lstat().st_ino == self.identity:
            self.path.unlink()
