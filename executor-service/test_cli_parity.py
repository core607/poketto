"""The Python client is a test-only oracle for the native CLI's wire and result contract."""
from concurrent.futures import ThreadPoolExecutor
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

from bridge import LeaseBridge, MAX_FRAME

ROOT = Path(__file__).parent
CLIENTS = ([sys.executable, '-I', str(ROOT / 'testdata/cli_reference.py')],
           [str(ROOT / 'bin/poketto')])


class CliParityTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.execution = str(uuid.uuid4())

    def run_client(self, client, command, reply=None, fault=None, supplied=b''):
        path = self.root / uuid.uuid4().hex
        bridge = LeaseBridge(path, os.getgid())
        bridge.reset_command(self.execution)
        environment = {**os.environ, 'POKETTO_BRIDGE': str(path), 'POKETTO_EXECUTION_ID': self.execution}
        if fault == 'inactive':
            environment['POKETTO_EXECUTION_ID'] = str(uuid.uuid4())
        elif fault == 'missing-state':
            (path / 'state').unlink()
        elif fault == 'missing-lock':
            (path / 'lock').unlink()
        elif fault == 'symlink-lock':
            (path / 'lock').unlink()
            (path / 'lock').symlink_to(path / 'state')
        elif fault == 'missing-fifo':
            (path / 'requests').unlink()
        elif fault == 'symlink-fifo':
            (path / 'requests').unlink()
            (path / 'requests').symlink_to(path / 'state')
        process = subprocess.Popen([*client, *command], stdin=subprocess.PIPE,
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=environment)
        request = None
        started = time.monotonic()
        try:
            with ThreadPoolExecutor(max_workers=1) as pool:
                output = pool.submit(process.communicate, supplied, timeout=65 if fault == 'timeout' else 5)
                if fault not in ('inactive', 'missing-state', 'missing-lock', 'symlink-lock',
                                 'missing-fifo', 'symlink-fifo', 'usage'):
                    request = bridge.poll(timeout=3)
                    self.assertIsNotNone(request)
                    if fault == 'closed':
                        bridge.reset_command()
                    elif fault != 'timeout':
                        result = path / 'responses' / (request['requestId'] + '.json')
                        if fault == 'symlink-response':
                            result.symlink_to(path / 'state')
                        elif fault == 'directory-response':
                            result.mkdir()
                        elif fault == 'fifo-response':
                            os.mkfifo(result)
                        elif fault in ('writable-response', 'oversize-response', 'bad-json', 'bad-utf8', 'bad-result'):
                            raw = {
                                'writable-response': b'{"ok": true}',
                                'oversize-response': b'x' * (MAX_FRAME + 1),
                                'bad-json': b'not json',
                                'bad-utf8': b'\xff',
                                'bad-result': b'{"ok": 1}',
                            }[fault]
                            result.write_bytes(raw)
                            result.chmod(0o666 if fault == 'writable-response' else 0o440)
                        else:
                            bridge.complete(request['requestId'], reply if reply is not None else {'ok': True})
                    stdout, stderr = output.result(timeout=65 if fault == 'timeout' else 5)
                    if not fault:
                        bridge.poll(timeout=0.05)
                        self.assertFalse(list((path / 'responses').iterdir()), 'The native client must ack a known result')
                else:
                    stdout, stderr = output.result(timeout=5)
            if request:
                request.pop('requestId')
            normalized = re.sub(rb'\([^)\n]+\)', b'(ERROR_CODE)', stderr) if b'Poketto bridge unavailable' in stderr else stderr
            return request, process.returncode, stdout, normalized, time.monotonic() - started
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
            for result in (path / 'responses').iterdir():
                if result.is_dir() and not result.is_symlink():
                    result.rmdir()
            bridge.close()

    def test_all_commands_preserve_requests_stdout_and_exit_status(self):
        text = self.root / 'text.txt'
        text.write_bytes('中文\n"quoted"\\literal\n\n'.encode())
        commands = [
            ['status'], ['sync'], ['recover'], ['recover', '--skip-local'],
            ['move', 'private/a.md', 'private/b.md'],
            ['save', 'a.md', 'b.md', '--delete', 'c.md', '--delete', 'd.md'],
            ['save', '--delete', 'a.md'],
            ['create', 'a.md', '--text', '汉字\n\n'],
            ['create', 'a.md', '--text-file', str(text)],
            ['create', 'a.md', '--stdin'],
            ['edit', 'a.md', '--old', 'old', '--new', ''],
            ['edit', 'a.md', '--old-file', str(text), '--new-file', str(text)],
            ['edit', 'a.md', '--old', 'old', '--new-stdin'],
            ['export', '.', '--output', 'all.zip'],
            ['export', 'private', 'public', '--output', 'all.zip', '--public'],
            ['artifact', 'create', 'result.bin'],
            ['artifact', 'create', 'result.bin', '--type', 'image/png'],
            ['artifact', 'remove', 'artifact-id'],
            ['media', 'list'],
            ['media', 'list', '--prefix', 'private/', '--offset', ' +0002 ', '--limit', '3',
             '--index-version', 'a' * 64, '--commit', 'b' * 40],
            ['media', 'fetch', 'private/cat.png'],
            ['media', 'fetch', 'private/cat.png', '--commit', 'c' * 40, '--output', 'cat.png'],
            ['media', 'import', 'cat.png', '--as', 'private/cat.png', '--key', 'synthetic-media-key'],
            ['media', 'import', 'cat.png', '--as', 'private/cat.png', '--key', 'synthetic-media-key',
             '--type', 'image/png', '--replace'],
            ['media', 'link', 'private/cat.png', '--asset', 'asset-id', '--revision', 'd' * 64],
            ['media', 'link', 'private/cat.png', '--asset', 'asset-id', '--revision', 'd' * 64, '--replace'],
        ]
        for command in commands:
            with self.subTest(command=command):
                replies = [self.run_client(client, command,
                    {'ok': True, 'result': {'message': '中文\n', 'items': [1, True, None, {'x': 'a'}]}},
                    supplied='中文 stdin\n\n'.encode()) for client in CLIENTS]
                self.assertEqual(replies[0][:4], replies[1][:4])
        for client in CLIENTS:
            self.assertEqual(1, self.run_client(client, ['status'], {'ok': False, 'code': 'ACCESS_DENIED'})[1])

    def test_usage_errors_never_send_a_request(self):
        commands = [[], ['unknown'], ['create', 'a'], ['create', 'a', '--stdin', '--text=x'],
                    ['edit', 'a', '--old=x'], ['save'], ['export', '.', '--public'],
                    ['media', 'list', '--limit=nope'], ['artifact'], ['status', '--unknown'],
                    ['create', 'a', '--text-file', str(self.root / 'missing')],
                    ['create', 'a', '--stdin']]
        for command in commands:
            with self.subTest(command=command):
                results = [self.run_client(client, command, fault='usage', supplied=b'x' * (MAX_FRAME + 1))
                           for client in CLIENTS]
                for result in results:
                    self.assertEqual((None, 2, b''), result[:3])

    def test_all_help_content_is_identical(self):
        pages = [[], ['status'], ['sync'], ['recover'], ['move'], ['save'], ['edit'], ['create'], ['export'],
                 ['artifact'], ['artifact', 'create'], ['artifact', 'remove'], ['media'], ['media', 'list'],
                 ['media', 'fetch'], ['media', 'import'], ['media', 'link']]
        for page in pages:
            with self.subTest(page=page):
                results = [subprocess.run([*client, *page, '--help'], capture_output=True, timeout=5,
                           env={**os.environ, 'COLUMNS': '80'}) for client in CLIENTS]
                # Help content is the contract; argparse wraps differently between Python releases.
                self.assertEqual(results[0].stdout.split(), results[1].stdout.split())
                self.assertEqual((0, 0), tuple(result.returncode for result in results))

    def test_bridge_failures_keep_the_unknown_outcome_message(self):
        for fault in ('inactive', 'missing-state', 'missing-lock', 'symlink-lock', 'missing-fifo',
                      'symlink-fifo', 'closed', 'symlink-response', 'directory-response', 'fifo-response',
                      'writable-response', 'oversize-response', 'bad-json', 'bad-utf8', 'bad-result'):
            with self.subTest(fault=fault):
                results = [self.run_client(client, ['status'], fault=fault) for client in CLIENTS]
                self.assertEqual(results[0][1:4], results[1][1:4])
                self.assertEqual((1, b''), results[1][1:3])
                self.assertIn(b'inspect host status before retrying a write', results[1][3])

    def test_reply_deadline_remains_fifty_five_seconds(self):
        with ThreadPoolExecutor(max_workers=2) as pool:
            pending = [pool.submit(self.run_client, client, ['status'], fault='timeout') for client in CLIENTS]
            results = [result.result(timeout=65) for result in pending]
        self.assertEqual(results[0][1:4], results[1][1:4])
        for result in results:
            self.assertEqual(1, result[1])
            self.assertGreaterEqual(result[4], 54.5)
            self.assertLess(result[4], 63)


if __name__ == '__main__':
    unittest.main()
