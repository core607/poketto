import base64
import json
import random
import threading
import time
import unittest
import uuid

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from worker import Service

LEASES = 12
LIVE_UNITS = 4
COMMANDS = 15


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


class Bridge:
    def reset_command(self):
        pass

    def close(self):
        pass


class UnitBackend:
    """Starts a unit lazily on a lease's first command, like the systemd backend, with fixed delays."""

    def __init__(self):
        self.lock = threading.Lock()
        self.live = set()
        self.most_live = 0

    def open(self, session, data):
        session.bridge = Bridge()

    def has_seed(self, workspace_id, scope, commit):
        return False

    def execute(self, session, data, on_start, on_ready):
        if session.channel is None:
            on_start()
            time.sleep(0.03)
            with self.lock:
                self.live.add(session.id)
                self.most_live = max(self.most_live, len(self.live))
            session.channel, session.unit = object(), 'unit-' + session.id
        on_ready()
        time.sleep(0.003)
        return {'exitCode': 0, 'terminationReason': 'normal', 'freshSandbox': False}

    def stop_unit(self, session):
        time.sleep(0.01)
        with self.lock:
            self.live.discard(session.id)
        session.unit, session.channel = '', None

    def close(self, session):
        with self.lock:
            self.live.discard(session.id)


class ConcurrentLeaseTests(unittest.TestCase):
    """Many leases share fewer live units while every lease runs commands and renews at once."""

    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.backend = UnitBackend()
        self.service = Service(self.key.public_key(), self.backend, {
            'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxRequests': 100000, 'idleUnitSeconds': 1800,
            'maxSessions': LEASES, 'maxBundleBytes': 1000, 'maxTimeoutMillis': 60000,
            'maxExecutionsPerSession': 1000, 'maxLiveUnits': LIVE_UNITS}, lambda: 1000)

    def send(self, identity, operation, data=None):
        p = {**identity, 'version': 1, 'operation': operation, 'requestId': str(uuid.uuid4()),
             'workerBootId': self.service.boot, 'issuedAt': 1000, 'expiresAt': 1015, 'data': data or {}}
        raw = json.dumps(p).encode()
        return self.service.handle({'payload': b64(raw), 'signature': b64(self.key.sign(raw))})

    def lease(self, index):
        identity = {'principalId': str(uuid.uuid4()), 'accountId': str(uuid.uuid4()),
                    'workspaceId': str(uuid.uuid4()), 'serverSessionHash': f'{index:064x}',
                    'appBootId': str(uuid.uuid4()), 'leaseId': str(uuid.uuid4())}
        opened = self.send(identity, 'OPEN', {
            'exportId': str(uuid.uuid4()), 'bundleSha256': 'b' * 64, 'bundleBytes': 80,
            'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'})
        self.assertTrue(opened['ok'], opened)
        return identity

    def test_commands_and_renewals_of_more_leases_than_live_units_never_lose_a_lease(self):
        leases = [self.lease(index) for index in range(LEASES)]
        commands, renewals, errors = [], [], []
        running = threading.Event()
        running.set()

        def agent(identity):
            try:
                for _ in range(COMMANDS):
                    answer = self.send(identity, 'EXEC', {
                        'executionId': str(uuid.uuid4()), 'commit': 'c' * 40,
                        'command': 'true', 'timeoutMillis': 1000})
                    commands.append(answer.get('code') if not answer['ok'] else 'OK')
                    time.sleep(random.uniform(0, 0.01))
            except Exception as error:
                errors.append(error)

        def renew(identity):
            try:
                while running.is_set():
                    answer = self.send(identity, 'RENEW')
                    renewals.append(answer['state'] if answer['ok'] else answer['code'])
                    time.sleep(0.005)
            except Exception as error:
                errors.append(error)

        agents = [threading.Thread(target=agent, args=(identity,)) for identity in leases]
        renewers = [threading.Thread(target=renew, args=(identity,)) for identity in leases]
        for thread in renewers + agents:
            thread.start()
        for thread in agents:
            thread.join(60)
        running.clear()
        for thread in renewers:
            thread.join(10)

        self.assertFalse([thread for thread in agents + renewers if thread.is_alive()], 'a request never returned')
        self.assertEqual([], errors)
        self.assertEqual(LEASES * COMMANDS, len(commands))
        # Refusing for capacity is expected when every live unit runs a command; nothing else is.
        self.assertLessEqual(set(commands), {'OK', 'EXECUTION_CAPACITY'})
        self.assertGreater(commands.count('OK'), LEASES)
        # A renewal answers every live state, UPDATING included, and never ends a lease.
        self.assertLessEqual(set(renewals), {'READY', 'RUNNING', 'UPDATING'})
        self.assertLessEqual(self.backend.most_live, LIVE_UNITS)
        for session in self.service.sessions.values():
            self.assertFalse(session.cancelled.is_set(), session.reason)
            self.assertEqual('READY', session.state)


if __name__ == '__main__':
    unittest.main()
