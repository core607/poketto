"""Replays the application's own request frames through this worker.

`java-frames.json` is written by the Java `WorkerFrameContractTests` from the records that build
every outgoing frame. Validating those exact bytes here is what keeps the two sides honest: a field
renamed, added or dropped on the Java side fails against the real dispatch rather than against a
second copy of the same assumption.

The positive case asserts only that a frame is not rejected as `INVALID_REQUEST`, because most
operations then fail for an unrelated reason: this suite establishes one session and never runs a
real command, so a mismatched execution id or an absent transfer is the expected next outcome. The
negative cases carry the real weight. Adding a key proves the application does not send a subset of
what the worker accepts, and removing one proves it does not send a superset.

The session is put into RUNNING with a live bridge because several operations check their field set
only after the session is ready; without that, their frames would never reach the check being
tested. Like the rest of this suite, it runs on Linux.
"""

import json
import os
import tempfile
import unittest
import uuid
from pathlib import Path

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

from bridge import LeaseBridge
from test_worker import Backend, b64
from worker import Service

SUITE = Path(__file__).parent
FRAMES = json.loads((SUITE / 'java-frames.json').read_text(encoding='utf-8'))

# CLOSE is the one operation whose field is optional: the worker reads an absent reason as
# 'session_closed', so dropping it leaves a valid frame rather than an invalid one.
OPTIONAL_FIELD_OPERATIONS = {'CLOSE'}


class JavaFrameTests(unittest.TestCase):
    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.backend = Backend()
        self.now = 1000
        self.service = Service(self.key.public_key(), self.backend,
                               {'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxRequests': 1000,
                                'maxSessions': 4, 'maxBundleBytes': 1 << 20, 'maxTimeoutMillis': 60000,
                                'maxExecutionsPerSession': 1000},
                               lambda: self.now)
        self.identity = {'principalId': str(uuid.uuid4()), 'accountId': str(uuid.uuid4()),
                         'workspaceId': str(uuid.uuid4()), 'serverSessionHash': 'a' * 64,
                         'appBootId': str(uuid.uuid4()), 'leaseId': str(uuid.uuid4())}
        self.assertTrue(self.send('OPEN', FRAMES['OPEN'])['ok'])
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', str(uuid.uuid4()), 'owned-unit'
        self.directory = tempfile.TemporaryDirectory()
        session.bridge = LeaseBridge(Path(self.directory.name) / 'bridge', os.getgid())
        self.addCleanup(self.directory.cleanup)
        self.addCleanup(session.bridge.close)

    def send(self, operation, data):
        payload = {**self.identity, 'version': 1, 'operation': operation,
                   'requestId': str(uuid.uuid4()), 'workerBootId': self.service.boot,
                   'issuedAt': self.now, 'expiresAt': self.now + 15, 'data': data}
        raw = json.dumps(payload).encode()
        return self.service.handle({'payload': b64(raw), 'signature': b64(self.key.sign(raw))})

    def test_every_frame_is_accepted_by_the_real_dispatch(self):
        for operation, data in FRAMES.items():
            with self.subTest(operation=operation):
                result = self.send(operation, data)
                self.assertNotEqual('INVALID_REQUEST', result.get('code'),
                                    f'{operation} frame {data} was rejected as malformed')

    def test_an_added_field_is_rejected(self):
        for operation, data in FRAMES.items():
            with self.subTest(operation=operation):
                result = self.send(operation, {**data, 'unexpectedField': 1})
                self.assertEqual('INVALID_REQUEST', result.get('code'),
                                 f'{operation} accepted an unexpected field')

    def test_a_removed_field_is_rejected(self):
        for operation, data in FRAMES.items():
            if operation in OPTIONAL_FIELD_OPERATIONS or not data:
                continue
            for field in data:
                with self.subTest(operation=operation, field=field):
                    reduced = {key: value for key, value in data.items() if key != field}
                    result = self.send(operation, reduced)
                    self.assertEqual('INVALID_REQUEST', result.get('code'),
                                     f'{operation} accepted a frame without {field}')

    def test_the_fixture_covers_every_operation_the_reference_documents(self):
        documented = set()
        for line in (SUITE / 'README.md').read_text(encoding='utf-8').splitlines():
            if line.startswith('| `') and '` |' in line:
                name = line.split('`')[1]
                if name.isupper() and name.replace('_', '').isalpha():
                    documented.add(name)
        self.assertEqual(documented - {'HELLO'}, set(FRAMES),
                         'the fixture and the worker reference disagree about the operation list')


if __name__ == '__main__':
    unittest.main()
