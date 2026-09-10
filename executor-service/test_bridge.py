from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

from bridge import BridgeRejected, LeaseBridge, MAX_FRAME, MAX_REQUESTS
from cli import BridgeUnavailable, _send, call


class LeaseBridgeTests(unittest.TestCase):
    def test_finished_commands_release_replay_budget_but_not_within_command(self):
        for index in range(MAX_REQUESTS + 1):
            request = {'requestId': str(uuid.uuid4()), 'operation': 'status', 'arguments': {}}
            _send(self.path, request, time.monotonic() + 2)
            self.assertEqual(request, self.bridge.poll(timeout=1))
            self.bridge.complete(request['requestId'], {'ok': True})
            self.bridge.reset_command()
        self.assertFalse(self.bridge.closed)
        self.assertFalse(list(self.bridge.responses.iterdir()))
        for index in range(MAX_REQUESTS):
            request = {'requestId': str(uuid.uuid4()), 'operation': 'status', 'arguments': {}}
            self.bridge._message(request)
            self.bridge.complete(request['requestId'], {'ok': True})
            self.bridge._message({'operation': 'ack', 'requestId': request['requestId']})
        with self.assertRaises(BridgeRejected):
            self.bridge._message(request)
        with self.assertRaises(BridgeRejected):
            self.bridge._message({**request, 'requestId': str(uuid.uuid4())})
        self.bridge.reset_command()
        self.assertEqual(request, self.bridge._message(request))

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.path = Path(self.temporary.name) / 'bridge'
        previous = os.umask(0o077)
        try:
            self.bridge = LeaseBridge(self.path, os.getgid())
        finally:
            os.umask(previous)
        self.assertEqual(0o750, self.path.stat().st_mode & 0o777)
        self.assertEqual(0o750, (self.path / 'responses').stat().st_mode & 0o777)
        self.addCleanup(self.bridge.close)

    def test_real_cli_waits_for_exact_host_result_and_reply_is_read_only(self):
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('cli.py')), 'status'],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={**os.environ, 'POKETTO_BRIDGE': str(self.path)})
        try:
            request = self.bridge.poll(timeout=2)
            self.assertEqual('status', request['operation'])
            self.assertIsNone(process.poll())
            self.assertIsNone(self.bridge.poll(timeout=0))
            with self.assertRaises(BridgeRejected):
                self.bridge.complete(str(uuid.uuid4()), {'ok': True})
            self.bridge.complete(request['requestId'], {'ok': True, 'result': {'commit': 'a' * 40}})
            response = self.path / 'responses' / (request['requestId'] + '.json')
            self.assertEqual(0o440, response.stat().st_mode & 0o777)
            stdout, stderr = process.communicate(timeout=3)
            self.assertEqual(0, process.returncode, stderr)
            self.assertEqual({'ok': True, 'result': {'commit': 'a' * 40}}, json.loads(stdout))
            self.bridge.poll(timeout=0.05)
            self.assertFalse(response.exists())
            with self.assertRaises(BridgeRejected):
                self.bridge.complete(request['requestId'], {'ok': True})
        finally:
            if process.poll() is None:
                process.kill()
            process.wait(timeout=3)

    def test_other_lease_cannot_complete_request_and_close_unblocks_waiter(self):
        with ThreadPoolExecutor() as pool:
            pending = pool.submit(call, self.path, 'save', {'writes': ['a.md'], 'deletes': []}, 3)
            request = self.bridge.poll(timeout=2)
            other = LeaseBridge(Path(self.temporary.name) / 'other', os.getgid())
            try:
                with self.assertRaises(BridgeRejected):
                    other.complete(request['requestId'], {'ok': True})
            finally:
                other.close()
            self.bridge.close()
            with self.assertRaises(BridgeUnavailable):
                pending.result(timeout=3)
            with self.assertRaises(BridgeRejected):
                self.bridge.poll(timeout=0)

    def test_invalid_frames_duplicates_and_replays_are_rejected(self):
        raw_values = [b'{}', b'{"requestId":"x","operation":"status","operation":"save","arguments":{}}',
                      b'"not an object"', None]
        for index, raw in enumerate(raw_values):
            bridge = LeaseBridge(Path(self.temporary.name) / str(index), os.getgid())
            try:
                fd = os.open(bridge.path / 'requests', os.O_WRONLY | os.O_NONBLOCK)
                try:
                    os.write(fd, struct.pack('!I', len(raw)) + raw if raw is not None else struct.pack('!I', MAX_FRAME + 1))
                finally:
                    os.close(fd)
                with self.assertRaises(BridgeRejected):
                    bridge.poll(timeout=1)
            finally:
                bridge.close()
        request = {'requestId': str(uuid.uuid4()), 'operation': 'status', 'arguments': {}}
        _send(self.path, request, time.monotonic() + 2)
        self.assertEqual(request, self.bridge.poll(timeout=1))
        _send(self.path, request, time.monotonic() + 2)
        with self.assertRaises(BridgeRejected):
            self.bridge.poll(timeout=1)

    def test_pending_capacity_and_reply_bound_recover_after_acknowledgement(self):
        requests = [{'requestId': str(uuid.uuid4()), 'operation': 'status', 'arguments': {}} for _ in range(5)]
        for request in requests[:4]:
            _send(self.path, request, time.monotonic() + 2)
            self.assertEqual(request, self.bridge.poll(timeout=1))
        _send(self.path, requests[4], time.monotonic() + 2)
        with self.assertRaises(BridgeRejected):
            self.bridge.poll(timeout=1)
        with self.assertRaises(BridgeRejected):
            self.bridge.complete(requests[0]['requestId'], {'value': 'x' * MAX_FRAME})
        for request in requests[:4]:
            self.bridge.complete(request['requestId'], {'ok': True})
            _send(self.path, {'requestId': request['requestId'], 'operation': 'ack'}, time.monotonic() + 2)
        self.bridge.poll(timeout=0.05)
        _send(self.path, requests[4], time.monotonic() + 2)
        self.assertEqual(requests[4], self.bridge.poll(timeout=1))


if __name__ == '__main__':
    unittest.main()
