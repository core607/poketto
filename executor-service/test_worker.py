import base64
import json
import threading
import unittest
import uuid

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

    def open(self, session, data):
        self.opened.append(session.id)
        if self.wait:
            self.wait.wait(3)

    def execute(self, session, data):
        self.executed.append(data)
        if self.wait:
            self.wait.wait(3)
        return {'exitCode': 0, 'terminationReason': session.reason}

    def close(self, session):
        self.closed.append(session.id)


class ProtocolTests(unittest.TestCase):
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
