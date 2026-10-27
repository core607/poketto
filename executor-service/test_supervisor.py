"""Protocol consumers exercise the independently built supervisor through HTTP on its Unix socket."""
import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
import uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey


class RustSupervisorTests(unittest.TestCase):
    def probe(self, name, count_key, expected):
        root = Path(__file__).resolve().parent
        process = subprocess.run([
            sys.executable, '-I', str(root / 'testdata/supervisor' / name),
            '--source', str(root), '--rust', str(root / 'poketto-supervisor-fixture'),
        ], capture_output=True, text=True, timeout=40)
        self.assertEqual(0, process.returncode, process.stdout + process.stderr)
        records = [json.loads(line) for line in process.stdout.splitlines() if line.startswith('{')]
        result = next(record for record in records if count_key in record)
        self.assertEqual('PASS', result['result'])
        self.assertEqual(expected, result[count_key])

    def test_signed_requests_replay_identity_capacity_and_revocation(self):
        self.probe('compare.py', 'differentialCases', 72)

    def test_bridge_and_concurrent_control_use_real_fifo_files(self):
        self.probe('full-control-check.py', 'bridgeAndControlChecks', 48)

    def test_handshake_and_configured_lease_are_not_replay_entries(self):
        self.probe('handshake-check.py', 'handshakeAndConfiguredLeaseChecks', 30)

    def test_installed_program_does_not_accept_the_fixture_entry(self):
        root = Path(__file__).resolve().parent
        with tempfile.TemporaryDirectory() as directory:
            socket = Path(directory) / 'forbidden.sock'
            key = Ed25519PrivateKey.generate()
            fixture = json.dumps({
                'fixtureOnly': True,
                'publicKey': base64.urlsafe_b64encode(key.public_key().public_bytes_raw()).decode().rstrip('='),
                'workerBootId': str(uuid.uuid4()), 'maxRequests': 1, 'maxConnections': 1, 'sessions': []}) + '\n'
            control = subprocess.run([str(root / 'poketto-supervisor-fixture'), str(socket), 'threads'],
                                     input=fixture + 'stop\n', capture_output=True, text=True, timeout=5)
            self.assertEqual(0, control.returncode, control.stdout + control.stderr)
            self.assertIn('"ready":true', control.stdout)
            result = subprocess.run([str(root / 'poketto-supervisor'), str(socket), 'threads'],
                                    input=fixture, capture_output=True, text=True, timeout=5)
            self.assertNotEqual(0, result.returncode)
            self.assertFalse(socket.exists())
            self.assertNotIn('"ready":true', result.stdout)
