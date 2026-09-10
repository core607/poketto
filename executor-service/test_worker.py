import base64
import json
import threading
import unittest
import uuid
import os
import tempfile
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from bridge import LeaseBridge
from cli import call
from session_files import capture_text

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from worker import Service


def uid():
    return str(uuid.uuid4())


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


class Backend:
    def __init__(self):
        self.opened = []
        self.executed = []
        self.closed = []
        self.wait = None
        self.entered = threading.Event()

    def open(self, session, data):
        self.opened.append(session.id)
        if self.wait:
            self.wait.wait(3)

    def execute(self, session, data):
        self.executed.append(data)
        self.entered.set()
        if self.wait:
            self.wait.wait(3)
        return {'exitCode': 0, 'terminationReason': session.reason}

    def close(self, session):
        self.closed.append(session.id)

    def capture(self, session, writes, deletes):
        return capture_text(self.root, writes, deletes)

    def mount_path(self, session):
        return self.root

    def install(self, session, incoming):
        return incoming.install(os.getuid(), os.getgid())


class ProtocolTests(unittest.TestCase):
    def test_signed_materialization_is_scoped_and_retains_an_acknowledged_install(self):
        import hashlib
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', uid(), 'owned-unit'
        with tempfile.TemporaryDirectory() as temporary:
            self.backend.root = Path(temporary)
            repository = self.backend.root / 'work/repository'
            repository.mkdir(parents=True)
            content = b'host selected contents'
            request = self.payload('MATERIALIZE_BEGIN', {'executionId': session.execution_id, 'path': 'new.md',
                'bytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(), 'expectedSha256': None, 'delete': False, 'allowIdentical': False})
            result = self.send(request)
            self.assertTrue(result['ok'], result)
            reference = {'executionId': session.execution_id, 'transferId': result['transferId']}
            self.assertEqual('MATERIALIZE_IN_PROGRESS', self.send(self.payload('MATERIALIZE_BEGIN', request['data']))['code'])
            chunk = self.payload('MATERIALIZE_CHUNK', {**reference, 'offset': 0, 'data': base64.b64encode(content).decode()})
            foreign = {**chunk, 'requestId': uid(), 'serverSessionHash': 'f' * 64}
            self.assertEqual('SESSION_NOT_FOUND', self.send(foreign)['code'])
            wrong = self.payload('MATERIALIZE_COMMIT', {**reference, 'executionId': uid()})
            self.assertEqual('EXECUTION_MISMATCH', self.send(wrong)['code'])
            self.assertTrue(self.send(chunk)['ok'])
            self.assertFalse((repository / 'new.md').exists())
            installed = self.send(self.payload('MATERIALIZE_COMMIT', reference))
            self.assertTrue(installed['ok'], installed)
            self.assertEqual(content, (repository / 'new.md').read_bytes())
            (repository / 'new.md').write_text('subsequent local edit')
            repeated = self.send(self.payload('MATERIALIZE_COMMIT', reference))
            self.assertEqual(installed['installed'], repeated['installed'])
            self.assertEqual('subsequent local edit', (repository / 'new.md').read_text())
            self.assertTrue(self.send(self.payload('MATERIALIZE_ABORT', reference))['ok'])
            self.assertIsNone(session.incoming)
            self.assertFalse(list(self.backend.root.glob('incoming-*')))

    def test_selected_capture_is_immutable_chunked_and_bound_to_execution_and_identity(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', uid(), 'owned-unit'
        with tempfile.TemporaryDirectory() as temporary:
            self.backend.root = Path(temporary)
            repository = self.backend.root / 'work/repository'
            repository.mkdir(parents=True)
            original = ('猫\r\n' * 30000).encode()
            (repository / 'selected.md').write_bytes(original)
            (repository / 'unselected.md').write_text('Keep me')
            request = self.payload('CAPTURE_BEGIN', {'executionId': session.execution_id,
                'writes': ['selected.md'], 'deletes': ['removed.md']})
            result = self.send(request)
            self.assertTrue(result['ok'], result)
            self.assertEqual(['selected.md'], [item['path'] for item in result['writes']])
            self.assertEqual(('removed.md',), result['deletes'])
            (repository / 'selected.md').write_text('Changed after capture')
            reference = {'executionId': session.execution_id, 'captureId': result['captureId']}
            self.assertEqual('CAPTURE_IN_PROGRESS', self.send(self.payload('CAPTURE_BEGIN', request['data']))['code'])
            read = self.payload('CAPTURE_READ', {**reference, 'index': 0, 'offset': 0, 'limit': 65536})
            foreign = {**read, 'requestId': uid(), 'serverSessionHash': 'f' * 64}
            self.assertEqual('SESSION_NOT_FOUND', self.send(foreign)['code'])
            wrong_execution = self.payload('CAPTURE_READ', {**read['data'], 'executionId': uid()})
            self.assertEqual('EXECUTION_MISMATCH', self.send(wrong_execution)['code'])
            captured = bytearray()
            while len(captured) < len(original):
                chunk = self.send(self.payload('CAPTURE_READ', {**read['data'], 'offset': len(captured)}))
                self.assertTrue(chunk['ok'], chunk)
                captured.extend(base64.b64decode(chunk['data']))
            self.assertEqual(original, captured)
            self.assertTrue(self.send(self.payload('CAPTURE_RELEASE', reference))['ok'])
            self.assertEqual('CAPTURE_NOT_FOUND', self.send(self.payload('CAPTURE_READ', read['data']))['code'])

    def test_signed_bridge_poll_and_completion_work_while_command_holds_operation_lock(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        with tempfile.TemporaryDirectory() as temporary, ThreadPoolExecutor() as pool:
            session.bridge = LeaseBridge(Path(temporary) / 'bridge', os.getgid())
            self.backend.wait = threading.Event()
            execution = self.execution()
            running = pool.submit(self.send, execution)
            try:
                self.assertTrue(self.backend.entered.wait(2))
                client = pool.submit(call, session.bridge.path, 'status', {}, 3)
                polled = self.send(self.payload('BRIDGE_POLL'))
                self.assertTrue(polled['ok'])
                self.assertEqual('RUNNING', polled['state'])
                self.assertEqual(execution['data']['executionId'], polled['executionId'])
                self.assertTrue(self.send(self.payload('RENEW'))['ok'])
                completion = {'bridgeRequestId': polled['bridgeRequest']['requestId'],
                    'executionId': uid(), 'response': {'ok': True}}
                self.assertEqual('EXECUTION_MISMATCH', self.send(self.payload('BRIDGE_COMPLETE', completion))['code'])
                completion['executionId'] = execution['data']['executionId']
                self.assertTrue(self.send(self.payload('BRIDGE_COMPLETE', completion))['ok'])
                self.assertEqual({'ok': True}, client.result(timeout=3))
            finally:
                self.backend.wait.set()
                running.result(timeout=3)
                session.bridge.close()

    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.backend = Backend()
        self.now = 1000
        self.service = Service(self.key.public_key(), self.backend,
            {'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxRequests': 100,
             'maxSessions': 2, 'maxBundleBytes': 1000, 'maxTimeoutMillis': 60000, 'maxExecutionsPerSession': 1000},
            lambda: self.now)
        self.identity = {'principalId': uid(), 'accountId': uid(), 'workspaceId': uid(),
                         'serverSessionHash': 'a' * 64, 'appBootId': uid(), 'leaseId': uid()}

    def payload(self, op, data=None):
        return {**self.identity, 'version': 1, 'operation': op, 'requestId': uid(),
                'workerBootId': self.service.boot, 'issuedAt': self.now,
                'expiresAt': self.now + 15, 'data': data or {}}

    def envelope(self, p, key=None):
        raw = json.dumps(p).encode()
        return {'payload': b64(raw), 'signature': b64((key or self.key).sign(raw))}

    def send(self, p):
        return self.service.handle(self.envelope(p))

    def opened(self):
        p = self.payload('OPEN', {'exportId': uid(), 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40})
        self.assertTrue(self.send(p)['ok'])
        return p

    def execution(self):
        return self.payload('EXEC', {'executionId': uid(), 'commit': 'c' * 40,
                                    'command': 'git log', 'timeoutMillis': 1000})

    def test_real_signature_and_modified_payload(self):
        p = self.payload('RENEW')
        result = self.service.handle(self.envelope(p, Ed25519PrivateKey.generate()))
        self.assertEqual('INVALID_SIGNATURE', result['code'])
        envelope = self.envelope(p)
        p['operation'] = 'CLOSE'
        envelope['payload'] = b64(json.dumps(p).encode())
        self.assertEqual('INVALID_SIGNATURE', self.service.handle(envelope)['code'])

    def test_restart_epoch_and_expiry(self):
        p = self.payload('RENEW')
        p['workerBootId'] = uid()
        self.assertEqual('WORKER_RESTARTED', self.send(p)['code'])
        p['workerBootId'] = self.service.boot
        self.now += 16
        self.assertEqual('LEASE_EXPIRED', self.send(p)['code'])

    def test_duplicate_execute_never_runs_twice(self):
        self.opened()
        p = self.execution()
        first = self.send(p)
        self.assertEqual(first, self.send(p))
        self.assertEqual(1, len(self.backend.executed))
        p['requestId'] = uid()
        self.assertEqual('EXECUTION_ALREADY_STARTED', self.send(p)['code'])

    def test_request_id_cannot_name_another_payload(self):
        p = self.opened()
        p['data']['commit'] = 'd' * 40
        self.assertEqual('REPLAY_CONFLICT', self.send(p)['code'])

    def test_identity_and_commit_cannot_change(self):
        self.opened()
        for key in ('principalId', 'accountId', 'workspaceId', 'appBootId', 'serverSessionHash'):
            p = self.execution()
            p[key] = 'd' * 64 if key == 'serverSessionHash' else uid()
            self.assertEqual('SESSION_NOT_FOUND', self.send(p)['code'])
        p = self.execution()
        p['data']['commit'] = 'e' * 40
        self.assertEqual('SESSION_COMMIT_MISMATCH', self.send(p)['code'])

    def test_revocation_blocks_renew_and_execute(self):
        self.opened()
        p = self.payload('REVOKE', {'keyIds': [self.identity['principalId']], 'accountIds': []})
        self.assertTrue(self.send(p)['ok'])
        self.assertEqual('AUTH_REVOKED', self.send(self.execution())['code'])
        self.assertEqual('AUTH_REVOKED', self.send(self.payload('RENEW'))['code'])
        self.assertEqual([self.identity['leaseId']], self.backend.closed)
        self.assertEqual('CLOSED', self.send(self.payload('CLOSE'))['state'])
        p = self.payload('CLOSE')
        p['principalId'] = uid()
        self.assertEqual('SESSION_NOT_FOUND', self.send(p)['code'])

    def test_initializer_can_renew_and_cancel(self):
        self.backend.wait = threading.Event()
        p = self.payload('OPEN', {'exportId': uid(), 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40})
        result = []
        thread = threading.Thread(target=lambda: result.append(self.send(p)))
        thread.start()
        for _ in range(10000):
            if self.backend.opened:
                break
            threading.Event().wait(0.001)
        self.now += 5
        self.assertEqual('INITIALIZING', self.send(self.payload('RENEW'))['state'])
        self.assertEqual('CLOSING', self.send(self.payload('CLOSE', {'reason': 'cancelled'}))['state'])
        self.backend.wait.set()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual('SESSION_CLOSED', result[0]['code'])
        self.assertEqual([self.identity['leaseId']], self.backend.closed)

    def test_close_poll_preserves_active_revocation_reason(self):
        self.opened()
        self.backend.wait = threading.Event()
        results = []
        thread = threading.Thread(target=lambda: results.append(self.send(self.execution())))
        thread.start()
        for _ in range(10000):
            if self.backend.executed:
                break
            threading.Event().wait(.001)
        revoked = self.send(self.payload('REVOKE', {'keyIds': [self.identity['principalId']], 'accountIds': []}))
        self.assertEqual('CLOSING', revoked['state'])
        self.assertEqual('CLOSING', self.send(self.payload('CLOSE'))['state'])
        self.backend.wait.set()
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertEqual('revoked', results[0]['result']['terminationReason'])
        self.assertEqual('CLOSED', self.send(self.payload('CLOSE'))['state'])

    def test_expired_lease_is_cleaned(self):
        self.opened()
        self.now += 16
        self.service.sweep()
        self.assertEqual([self.identity['leaseId']], self.backend.closed)

    def test_close_before_open_prevents_late_creation(self):
        self.assertEqual('CLOSED', self.send(self.payload('CLOSE'))['state'])
        p = self.payload('OPEN', {'exportId': uid(), 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40})
        self.assertEqual('SESSION_CLOSED', self.send(p)['code'])
        self.assertFalse(self.backend.opened)

    def test_account_revocation_before_open_prevents_creation(self):
        self.assertTrue(self.send(self.payload('REVOKE', {'keyIds': [], 'accountIds': [self.identity['accountId']]}))['ok'])
        p = self.payload('OPEN', {'exportId': uid(), 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40})
        self.assertEqual('AUTH_REVOKED', self.send(p)['code'])
        self.assertFalse(self.backend.opened)

    def test_per_session_replay_capacity_is_bounded(self):
        self.service.config['maxExecutionsPerSession'] = 1
        self.opened()
        self.assertTrue(self.send(self.execution())['ok'])
        self.assertEqual('EXECUTION_CAPACITY', self.send(self.execution())['code'])

    def test_limits_and_no_path_fields(self):
        p = self.payload('OPEN', {'exportId': '../source', 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40})
        self.assertEqual('INVALID_REQUEST', self.send(p)['code'])
        self.opened()
        p = self.execution()
        p['data']['command'] = '\0'
        self.assertEqual('INVALID_REQUEST', self.send(p)['code'])
        p = self.execution()
        p['data']['hostPath'] = '/etc'
        self.assertEqual('INVALID_REQUEST', self.send(p)['code'])
        self.assertFalse(self.backend.executed)


if __name__ == '__main__':
    unittest.main()
