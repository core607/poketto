"""Replays the application's own request bodies through the Rust supervisor.

`java-frames.json` is written by the Java `WorkerFrameContractTests` from the records that build
every outgoing request. Validating those exact bytes against the supervisor fixture keeps the two
sides honest: a field renamed, added or dropped on the Java side fails against the real dispatch
rather than against a second copy of the same assumption.

The positive case asserts only that a request is not rejected as `INVALID_REQUEST`, because most
operations then fail for an unrelated reason: the fixture serves one lease with a running execution
and a live mailbox but no sandbox or copy, so a mismatched execution or an absent volume is the
expected next outcome. The negative cases carry the real weight. Adding a key proves the
application does not send a subset of what the supervisor accepts, and removing one proves it does
not send a superset.
"""

import base64
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import unittest
import uuid

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey

SUITE = Path(__file__).parent
FRAMES = json.loads((SUITE / 'java-frames.json').read_text(encoding='utf-8'))

# CLOSE is the one operation whose field is optional: an absent reason reads as 'session_closed',
# so dropping it leaves a valid request rather than an invalid one.
OPTIONAL_FIELD_OPERATIONS = {'CLOSE'}


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def post(path, body):
    head = f'POST /v1/requests HTTP/1.1\r\nHost: sandcrab\r\nContent-Length: {len(body)}\r\n\r\n'
    with socket.socket(socket.AF_UNIX) as stream:
        stream.settimeout(10)
        stream.connect(path)
        stream.sendall(head.encode() + body)
        response = b''
        while part := stream.recv(65536):
            response += part
    return json.loads(response.partition(b'\r\n\r\n')[2])


class JavaFrameTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.key = Ed25519PrivateKey.generate()
        cls.directory = tempfile.TemporaryDirectory()
        root = Path(cls.directory.name)
        cls.socket = str(root / 'supervisor.sock')
        cls.identity = {'principalId': str(uuid.uuid4()), 'accountId': str(uuid.uuid4()),
                        'workspaceId': str(uuid.uuid4()), 'serverSessionHash': 'a' * 64,
                        'appBootId': str(uuid.uuid4()), 'leaseId': str(uuid.uuid4())}
        cls.boot = str(uuid.uuid4())
        fixture = {'fixtureOnly': True, 'publicKey': b64(cls.key.public_key().public_bytes_raw()),
                   'workerBootId': cls.boot, 'maxRequests': 100000, 'maxConnections': 16,
                   'sessions': [{**cls.identity, 'deadline': time.time() + 3600,
                                 'executionId': str(uuid.uuid4()), 'bridgePath': str(root / 'bridge'),
                                 'execGid': os.getgid()}]}
        cls.process = subprocess.Popen([str(SUITE / 'poketto-supervisor-fixture'), cls.socket, 'threads'],
                                       stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
        cls.process.stdin.write(json.dumps(fixture) + '\n')
        cls.process.stdin.flush()
        assert json.loads(cls.process.stdout.readline())['ready'] is True

    @classmethod
    def tearDownClass(cls):
        cls.process.stdin.write('stop\n')
        cls.process.stdin.flush()
        cls.process.wait(10)
        cls.directory.cleanup()

    def send(self, operation, data):
        issued = int(time.time())
        payload = {**self.identity, 'version': 1, 'operation': operation, 'requestId': str(uuid.uuid4()),
                   'workerBootId': self.boot, 'issuedAt': issued, 'expiresAt': issued + 15, 'data': data}
        raw = json.dumps(payload).encode()
        envelope = {'payload': b64(raw), 'signature': b64(self.key.sign(raw))}
        return post(self.socket, json.dumps(envelope).encode())

    def test_every_frame_is_accepted_by_the_real_dispatch(self):
        for operation, data in FRAMES.items():
            if operation in ('CLOSE', 'REVOKE'):
                continue  # Either would end the fixture's only lease; their fields are checked below.
            with self.subTest(operation=operation):
                result = self.send(operation, data)
                self.assertNotEqual('INVALID_REQUEST', result.get('code'),
                                    f'{operation} request {data} was rejected as malformed')

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
                                     f'{operation} accepted a request without {field}')

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
