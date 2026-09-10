from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import socket
import struct
import tempfile
import time
import unittest

from bridge import BridgeRejected, LeaseBridge, MAX_FRAME, _read, encode


def call(path, payload=None):
    with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
        connection.settimeout(3)
        connection.connect(str(path))
        data = payload if payload is not None else encode({'operation': 'status', 'arguments': {}})
        connection.sendall(struct.pack('!I', len(data)) + data)
        size, = struct.unpack('!I', _read(connection, 4, time.monotonic() + 3))
        return json.loads(_read(connection, size, time.monotonic() + 3))


class LeaseBridgeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'bridge.sock'
        self.bridge = LeaseBridge(self.path, os.getuid(), os.getgid())
        self.addCleanup(self.bridge.close)

    def test_real_socket_request_is_delivered_once_and_completed_by_exact_host_id(self):
        with ThreadPoolExecutor() as pool:
            pending = pool.submit(call, self.path)
            request = self.bridge.poll(timeout=2)
            self.assertEqual('status', request['operation'])
            self.assertIsNone(self.bridge.poll(timeout=0))
            with self.assertRaises(BridgeRejected):
                self.bridge.complete('another-session-request', {'ok': True})
            self.bridge.complete(request['requestId'], {'ok': True, 'result': {'commit': 'a' * 40}})
            self.assertEqual({'ok': True, 'result': {'commit': 'a' * 40}}, pending.result(timeout=3))
            with self.assertRaises(BridgeRejected):
                self.bridge.complete(request['requestId'], {'ok': True})

    def test_other_lease_cannot_complete_request_and_close_unblocks_waiter(self):
        with ThreadPoolExecutor() as pool:
            pending = pool.submit(call, self.path)
            request = self.bridge.poll(timeout=2)
            other = LeaseBridge(Path(self.temporary.name) / 'other.sock', os.getuid(), os.getgid())
            try:
                with self.assertRaises(BridgeRejected):
                    other.complete(request['requestId'], {'ok': True})
            finally:
                other.close()
            self.bridge.close()
            with self.assertRaises((BridgeRejected, OSError)):
                pending.result(timeout=3)
            self.assertFalse(self.path.exists())
            with self.assertRaises(BridgeRejected):
                self.bridge.poll(timeout=0)

    def test_invalid_frames_and_unexpected_peer_do_not_enter_mailbox(self):
        for payload in [b'{}', b'{"operation":"status","operation":"save","arguments":{}}',
                        b'{"operation":"arbitrary-shell","arguments":{}}', b'x' * (MAX_FRAME + 1)]:
            with self.subTest(size=len(payload)), self.assertRaises((BridgeRejected, OSError)):
                call(self.path, payload)
            self.assertIsNone(self.bridge.poll(timeout=0))
        self.bridge.close()
        wrong_peer = LeaseBridge(self.path, os.getuid() + 1, os.getgid())
        try:
            with self.assertRaises((BridgeRejected, OSError)):
                call(self.path)
            self.assertIsNone(wrong_peer.poll(timeout=0))
        finally:
            wrong_peer.close()

    def test_pending_capacity_rejects_excess_and_recovers_after_completion(self):
        with ThreadPoolExecutor() as pool:
            waiting = [pool.submit(call, self.path) for _ in range(4)]
            requests = [self.bridge.poll(timeout=2) for _ in waiting]
            self.assertTrue(all(requests))
            with self.assertRaises((BridgeRejected, OSError)):
                call(self.path)
            with self.assertRaises(BridgeRejected):
                self.bridge.complete(requests[0]['requestId'], {'value': 'x' * MAX_FRAME})
            for request in requests:
                self.bridge.complete(request['requestId'], {'ok': True})
            for future in waiting:
                self.assertEqual({'ok': True}, future.result(timeout=3))
            next_request = pool.submit(call, self.path)
            request = self.bridge.poll(timeout=2)
            self.bridge.complete(request['requestId'], {'ok': True})
            self.assertEqual({'ok': True}, next_request.result(timeout=3))


if __name__ == '__main__':
    unittest.main()
