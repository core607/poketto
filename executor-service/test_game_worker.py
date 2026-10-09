import base64
import json
import threading
import unittest
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from game_worker import GameService, GameBackend, game_input, encoded
from worker import Rejected, Session


class GameProtocolTests(unittest.TestCase):
    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.backend = Mock()
        self.backend.game.return_value = {'state': {}, 'observation': {'text': 'Ready', 'actions': []}, 'presentation': None}
        self.config = {'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxSessions': 1, 'maxRequests': 20}
        self.service = GameService(self.key.public_key(), self.backend, self.config, clock=lambda: 100)
        self.data = {'bundle': {'protocol': 1, 'source': 'export function init(){}',
                                'presentation': None, 'resources': {}},
                     'request': {'mode': 'init', 'state': None, 'action': None, 'seed': 1}}

    def envelope(self, operation='GAME', data=None, identity=None):
        payload = {'version': 1, 'workerBootId': self.service.boot, 'appBootId': str(uuid.uuid4()),
                   'operation': operation, 'requestId': str(uuid.uuid4()), 'issuedAt': 100, 'expiresAt': 115,
                   'principalId': str(uuid.uuid4()), 'accountId': str(uuid.uuid4()),
                   'workspaceId': str(uuid.uuid4()), 'serverSessionHash': '0' * 64,
                   'leaseId': str(uuid.uuid4()), 'data': self.data if data is None else data}
        if identity:
            payload.update({key: identity[key] for key in ('principalId', 'accountId', 'workspaceId')})
        raw = json.dumps(payload).encode()
        return {'payload': base64.urlsafe_b64encode(raw).decode().rstrip('='),
                'signature': base64.urlsafe_b64encode(self.key.sign(raw)).decode().rstrip('=')}

    def test_signed_job_replay_returns_one_result_and_releases_admission_after_cleanup(self):
        request = self.envelope()
        first = self.service.handle(request)
        self.assertTrue(first['ok'])
        self.assertEqual(first, self.service.handle(request))
        self.backend.game.assert_called_once()
        self.backend.close.assert_called_once()
        self.assertEqual({}, self.service.sessions)
        self.assertEqual(0, self.service.hello()['codeActProtocol'])
        self.assertEqual(1, self.service.hello()['gameProtocol'])

    def test_repository_commands_and_bridges_are_not_exposed(self):
        for operation in ('OPEN', 'EXEC', 'ATTACH', 'BRIDGE_POLL', 'MATERIALIZE_BEGIN'):
            self.assertEqual('INVALID_REQUEST', self.service.handle(self.envelope(operation))['code'])
        self.backend.game.assert_not_called()

    def test_revocation_marks_running_job_without_racing_initialization_and_prevents_new_jobs(self):
        request = self.envelope()
        identity = json.loads(base64.urlsafe_b64decode(request['payload'] + '=' * (-len(request['payload']) % 4)))
        entered, release = threading.Event(), threading.Event()

        def run(session, _):
            entered.set()
            self.assertTrue(session.cancelled.wait(3))
            self.backend.close.assert_not_called()
            release.wait(3)
            return self.backend.game.return_value

        self.backend.game.side_effect = run
        with ThreadPoolExecutor(2) as pool:
            first = pool.submit(self.service.handle, request)
            self.assertTrue(entered.wait(2))
            revoked = self.service.handle(self.envelope('REVOKE', {'keyIds': [identity['principalId']], 'accountIds': []}, identity))
            self.assertEqual('CLOSING', revoked['state'])
            release.set()
            self.assertEqual('GAME_EXPIRED', first.result(3)['code'])
        self.backend.close.assert_called_once()
        self.assertEqual('AUTH_REVOKED', self.service.handle(self.envelope(identity=identity))['code'])

    def test_concurrent_job_uses_separate_finite_admission_and_failed_containment_keeps_slot(self):
        entered, release = threading.Event(), threading.Event()

        def run(*_):
            entered.set()
            release.wait(3)
            return {'state': {}, 'observation': {'text': 'Ready', 'actions': []}, 'presentation': None}

        self.backend.game.side_effect = run
        with ThreadPoolExecutor(2) as pool:
            first = pool.submit(self.service.handle, self.envelope())
            self.assertTrue(entered.wait(2))
            self.assertEqual('GAME_CAPACITY', self.service.handle(self.envelope())['code'])
            release.set()
            self.assertTrue(first.result(3)['ok'])
        self.backend.close.side_effect = RuntimeError('Descendants remain')
        self.assertFalse(self.service.handle(self.envelope())['ok'])
        self.assertEqual(1, len(self.service.sessions))
        self.assertEqual('GAME_CAPACITY', self.service.handle(self.envelope())['code'])

    def test_shutdown_denies_new_jobs_and_drains_handlers_before_global_cleanup(self):
        entered, cancelled, release = threading.Event(), threading.Event(), threading.Event()

        def run(session, _):
            entered.set()
            self.assertTrue(session.cancelled.wait(3))
            cancelled.set()
            release.wait(3)
            return self.backend.game.return_value

        self.backend.game.side_effect = run
        with ThreadPoolExecutor(2) as pool:
            active = pool.submit(self.service.handle, self.envelope())
            self.assertTrue(entered.wait(2))
            stopping = pool.submit(self.service.shutdown)
            self.assertTrue(cancelled.wait(2))
            self.assertFalse(stopping.done())
            self.assertEqual('WORKER_STOPPING', self.service.handle(self.envelope())['code'])
            self.backend.close.assert_not_called()
            release.set()
            stopping.result(3)
            self.assertEqual('GAME_EXPIRED', active.result(3)['code'])
        self.backend.close.assert_called_once()

    def test_game_policy_has_no_bridge_or_repository_and_keeps_full_bounded_output(self):
        backend = object.__new__(GameBackend)
        backend.c = {'toolsRoot': '/opt/poketto/tools'}
        backend.sessions = Path('/run/poketto-game/sessions')
        session = Session(str(uuid.uuid4()), (), '', 115)
        policy = backend.sandbox_policy(session, {'mode': 'game'})
        self.assertEqual(['/tmp'], policy['filesystem']['allowWrite'])
        self.assertNotIn('bridge', json.dumps(policy))
        self.assertNotIn('repository', json.dumps(policy))
        self.assertEqual([], policy['network']['allowedDomains'])
        output = backend.run_output([b'x' * 32768, b''], [False, False])
        self.assertEqual(32768, len(output['stdout']))
        with self.assertRaises(Rejected):
            backend.run_output([b'x', b''], [True, False])

    def test_full_unicode_state_budget_depth_and_shape_are_checked_before_dispatch(self):
        self.assertGreater(len(game_input(self.data)), 0)
        for invalid in ({'shell': 'id'}, {'bundle': {}, 'request': {}}):
            with self.assertRaises(Rejected):
                game_input(invalid)
        with self.assertRaises(Rejected):
            encoded({'text': '猫' * 12000}, 32768)
        deep = {}
        for _ in range(18):
            deep = {'next': deep}
        with self.assertRaises(Rejected):
            encoded(deep, 32768)
