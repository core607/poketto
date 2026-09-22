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
from unittest.mock import patch
import uuid

from bridge import BridgeRejected, LeaseBridge, MAX_FRAME, MAX_REQUESTS
from cli import BridgeUnavailable, _send, call


class LeaseBridgeTests(unittest.TestCase):
    def test_background_requests_from_a_finished_execution_cannot_enter_the_next_command(self):
        previous = self.execution_id
        self.execution_id = str(uuid.uuid4())
        self.bridge.reset_command(self.execution_id)
        stale = {'requestId': str(uuid.uuid4()), 'executionId': previous, 'operation': 'save', 'arguments': {}}
        _send(self.path, stale, time.monotonic() + 2)
        self.assertIsNone(self.bridge.poll(timeout=0.05))
        self.assertFalse(self.bridge.pending)
        with self.assertRaisesRegex(BridgeUnavailable, 'not active'):
            call(self.path, 'save', {}, timeout=0.1)
        current = {**stale, 'executionId': self.execution_id}
        _send(self.path, current, time.monotonic() + 2)
        self.assertEqual(current, self.bridge.poll(timeout=1))

    def test_stdin_and_files_preserve_large_utf8_text_through_the_real_bridge(self):
        text = ('中文 "$HOME" `not a command` \'quoted\'\n' * 1500) + '\n'
        source = Path(self.temporary.name) / 'article.txt'
        source.write_text(text, encoding='utf-8')
        for command, operation, arguments, supplied in (
                (['create', 'private/article.md', '--stdin'], 'create',
                 {'path': 'private/article.md', 'text': text}, text.encode('utf-8')),
                (['create', 'private/article.md', '--text-file', str(source)], 'create',
                 {'path': 'private/article.md', 'text': text}, b''),
                (['edit', 'private/article.md', '--old-file', str(source), '--new-stdin'], 'edit',
                 {'path': 'private/article.md', 'oldText': text, 'newText': ''}, b'')):
            with self.subTest(command=command):
                process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('cli.py')), *command],
                    stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    env={**os.environ, 'POKETTO_BRIDGE': str(self.path)})
                try:
                    with ThreadPoolExecutor(max_workers=1) as pool:
                        output = pool.submit(process.communicate, supplied, timeout=5)
                        request = self.bridge.poll(timeout=3)
                        self.assertEqual(operation, request['operation'])
                        self.assertEqual(arguments, request['arguments'])
                        self.bridge.complete(request['requestId'], {'ok': True})
                        stdout, stderr = output.result(timeout=5)
                    self.assertEqual(0, process.returncode, stderr)
                    self.assertEqual({'ok': True}, json.loads(stdout))
                    self.bridge.poll(timeout=0.05)
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=3)

    def test_oversized_stdin_fails_before_a_host_write(self):
        process = subprocess.run([sys.executable, str(Path(__file__).with_name('cli.py')),
            'create', 'private/article.md', '--stdin'], input=b'x' * (MAX_FRAME + 1),
            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            env={**os.environ, 'POKETTO_BRIDGE': str(self.path)}, timeout=5)
        self.assertNotEqual(0, process.returncode)
        self.assertIn(b'512 KiB', process.stderr)
        self.assertIsNone(self.bridge.poll(timeout=0.05))

    def test_finished_commands_release_replay_budget_but_not_within_command(self):
        for index in range(MAX_REQUESTS + 1):
            request = {'requestId': str(uuid.uuid4()), 'executionId': self.execution_id, 'operation': 'status', 'arguments': {}}
            _send(self.path, request, time.monotonic() + 2)
            self.assertEqual(request, self.bridge.poll(timeout=1))
            self.bridge.complete(request['requestId'], {'ok': True})
            self.bridge.reset_command(self.execution_id)
        self.assertFalse(self.bridge.closed)
        self.assertFalse(list(self.bridge.responses.iterdir()))
        for index in range(MAX_REQUESTS):
            request = {'requestId': str(uuid.uuid4()), 'executionId': self.execution_id, 'operation': 'status', 'arguments': {}}
            self.bridge._message(request)
            self.bridge.complete(request['requestId'], {'ok': True})
            self.bridge._message({'operation': 'ack', 'requestId': request['requestId'], 'executionId': self.execution_id})
        with self.assertRaises(BridgeRejected):
            self.bridge._message(request)
        with self.assertRaises(BridgeRejected):
            self.bridge._message({**request, 'requestId': str(uuid.uuid4())})
        self.bridge.reset_command(self.execution_id)
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
        self.execution_id = str(uuid.uuid4())
        self.bridge.reset_command(self.execution_id)
        environment = patch.dict(os.environ, {'POKETTO_EXECUTION_ID': self.execution_id})
        environment.start()
        self.addCleanup(environment.stop)

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

    def test_artifact_cli_operations_cross_the_actual_fifo_allowlist(self):
        identifier = str(uuid.uuid4())
        for command, operation, arguments in (
                (['edit', 'private/article.md', '--old', 'old text', '--new', 'new text'],
                 'edit', {'path': 'private/article.md', 'oldText': 'old text', 'newText': 'new text'}),
                (['create', 'private/article.md', '--text', 'new text'],
                 'create', {'path': 'private/article.md', 'text': 'new text'}),
                (['media', 'link', 'private/cat.png', '--asset', identifier, '--revision', 'a' * 64],
                 'media_link', {'path': 'private/cat.png', 'assetId': identifier, 'revision': 'a' * 64, 'replace': False}),
                (['media', 'link', 'private/cat.png', '--asset', identifier, '--revision', 'a' * 64, '--replace'],
                 'media_link', {'path': 'private/cat.png', 'assetId': identifier, 'revision': 'a' * 64, 'replace': True}),
                (['recover', '--skip-local'], 'recover', {'skipLocal': True}),
                (['move', 'private/a.md', 'private/b.md'], 'move', {'source': 'private/a.md', 'destination': 'private/b.md'}),
                (['export', 'articles', 'media', '--output', 'bundle.zip', '--public'], 'export',
                 {'paths': ['articles', 'media'], 'output': 'bundle.zip', 'publicOnly': True}),
                (['export', '.', '--output', 'all.zip'], 'export',
                 {'paths': ['.'], 'output': 'all.zip', 'publicOnly': False}),
                (['artifact', 'create', 'result.bin', '--type', 'application/pdf'],
                 'artifact_create', {'path': 'result.bin', 'mediaType': 'application/pdf'}),
                (['artifact', 'remove', identifier], 'artifact_remove', {'artifactId': identifier})):
            with self.subTest(operation=operation):
                process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('cli.py')), *command],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={**os.environ, 'POKETTO_BRIDGE': str(self.path)})
                try:
                    request = self.bridge.poll(timeout=2)
                    self.assertEqual(operation, request['operation'])
                    self.assertEqual(arguments, request['arguments'])
                    self.bridge.complete(request['requestId'], {'ok': True})
                    stdout, stderr = process.communicate(timeout=3)
                    self.assertEqual(0, process.returncode, stderr)
                    self.assertEqual({'ok': True}, json.loads(stdout))
                    self.bridge.poll(timeout=0.05)
                finally:
                    if process.poll() is None:
                        process.kill()
                        process.wait(timeout=3)

    def test_media_list_cli_passes_paging_and_version_through_the_actual_fifo(self):
        process = subprocess.Popen([sys.executable, str(Path(__file__).with_name('cli.py')),
            'media', 'list', '--prefix', 'private/music/', '--offset', '2', '--limit', '3',
            '--index-version', 'a' * 64, '--commit', 'b' * 40],
            stdout=subprocess.PIPE, stderr=subprocess.PIPE, env={**os.environ, 'POKETTO_BRIDGE': str(self.path)})
        try:
            request = self.bridge.poll(timeout=2)
            self.assertEqual('media_list', request['operation'])
            self.assertEqual({'prefix': 'private/music/', 'offset': 2, 'limit': 3,
                              'indexVersion': 'a' * 64, 'commit': 'b' * 40}, request['arguments'])
            self.bridge.complete(request['requestId'], {'ok': True, 'result': {'items': [], 'nextOffset': None}})
            stdout, stderr = process.communicate(timeout=3)
            self.assertEqual(0, process.returncode, stderr)
            self.assertEqual([], json.loads(stdout)['result']['items'])
            self.bridge.poll(timeout=0.05)
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
        request = {'requestId': str(uuid.uuid4()), 'executionId': self.execution_id, 'operation': 'status', 'arguments': {}}
        _send(self.path, request, time.monotonic() + 2)
        self.assertEqual(request, self.bridge.poll(timeout=1))
        _send(self.path, request, time.monotonic() + 2)
        with self.assertRaises(BridgeRejected):
            self.bridge.poll(timeout=1)

    def test_pending_capacity_and_reply_bound_recover_after_acknowledgement(self):
        requests = [{'requestId': str(uuid.uuid4()), 'executionId': self.execution_id, 'operation': 'status', 'arguments': {}} for _ in range(5)]
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
            _send(self.path, {'requestId': request['requestId'], 'operation': 'ack', 'executionId': self.execution_id}, time.monotonic() + 2)
        self.bridge.poll(timeout=0.05)
        _send(self.path, requests[4], time.monotonic() + 2)
        self.assertEqual(requests[4], self.bridge.poll(timeout=1))


if __name__ == '__main__':
    unittest.main()
