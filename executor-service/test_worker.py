import base64
import json
import threading
import unittest
import uuid
import os
import tempfile
import time
from unittest.mock import Mock, patch
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from bridge import LeaseBridge
from cli import call, _send
from session_files import capture_text
from binary_capture import BinaryCapture
from artifacts import ArtifactStore

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from worker import Rejected, Service, Session, SystemdBackend, load_config


def uid():
    return str(uuid.uuid4())


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


class Backend:
    def __init__(self):
        self.opened = []
        self.executed = []
        self.closed = []
        self.stopped = []
        self.wait = None
        self.entered = threading.Event()

    def open(self, session, data):
        self.opened.append(session.id)
        self.open_data = data
        if self.wait:
            self.wait.wait(3)

    def has_seed(self, workspace_id, scope, commit):
        return (workspace_id, scope, commit) in getattr(self, 'seeds', set())

    def execute(self, session, data, on_start, on_ready):
        if session.channel is None:
            on_start()
        on_ready()
        self.executed.append(data)
        self.entered.set()
        if self.wait:
            self.wait.wait(3)
        return {'exitCode': 0, 'terminationReason': session.reason}

    def close(self, session):
        self.closed.append(session.id)

    def stop_unit(self, session):
        self.stopped.append(session.id)
        session.unit = ''
        session.channel = None

    def capture(self, session, writes, deletes):
        return capture_text(self.root, writes, deletes)

    def capture_binary(self, session, path):
        return BinaryCapture(self.root, path)

    def artifact(self, session, path, media_type):
        return session.artifacts.capture(path, media_type, session.cancelled.is_set)

    def mount_path(self, session):
        return self.root

    def install(self, session, incoming):
        return incoming.install(os.getuid(), os.getgid())

    def check_move(self, session, incoming):
        return incoming.check()


class ProtocolTests(unittest.TestCase):
    def test_idle_cleanup_stops_the_unit_but_preserves_the_renewable_lease(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.channel, session.unit, session.bridge = object(), 'owned-unit', Mock()
        self.service.config['idleUnitSeconds'] = 2
        self.now += 1
        self.service.sweep()
        self.assertFalse(self.backend.stopped)
        self.now += 1
        self.service.sweep()
        self.assertEqual([session.id], self.backend.stopped)
        self.assertFalse(self.backend.closed)
        self.assertEqual('READY', session.state)
        self.assertTrue(self.send(self.payload('RENEW'))['ok'])
        self.assertTrue(self.send(self.execution())['ok'])
        session.bridge.reset_command.assert_called_once_with()

    def test_a_fresh_unit_stops_the_least_recently_used_idle_unit_at_the_live_bound(self):
        self.service.config['maxLiveUnits'] = 1
        self.opened()
        first = self.service.sessions[self.identity['leaseId']]
        first.channel, first.unit, first.bridge = object(), 'first-unit', Mock()
        self.identity = {**self.identity, 'leaseId': uid(), 'serverSessionHash': 'b' * 64}
        self.opened()
        second = self.service.sessions[self.identity['leaseId']]
        second.bridge = Mock()

        first.state = 'RUNNING'
        refused = self.send(self.execution())
        self.assertEqual('EXECUTION_CAPACITY', refused['code'])
        self.assertEqual('READY', second.state)
        self.assertFalse(second.cancelled.is_set())
        self.assertFalse(self.backend.stopped)

        first.state = 'READY'
        self.assertTrue(self.send(self.execution())['ok'])
        self.assertEqual([first.id], self.backend.stopped)
        self.assertEqual('READY', first.state)
        self.assertFalse(first.cancelled.is_set())

    def test_a_command_waits_for_its_own_unit_being_stopped_instead_of_a_refusal(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.channel, session.unit, session.bridge = object(), 'own-unit', Mock()
        # Another lease's make_room, or idle cleanup, is stopping this idle unit.
        session.state = 'UPDATING'

        def finish_stopping():
            time.sleep(0.2)
            with self.service.lock:
                session.channel, session.state = None, 'READY'

        threading.Thread(target=finish_stopping).start()
        result = self.send(self.execution())
        self.assertTrue(result['ok'], result)

    def test_waiting_for_the_own_unit_and_making_room_share_one_budget(self):
        self.service.config['maxLiveUnits'] = 1
        self.opened()
        other = self.service.sessions[self.identity['leaseId']]
        other.channel, other.unit, other.bridge = object(), 'other-unit', Mock()
        other.state = 'UPDATING'
        self.identity = {**self.identity, 'leaseId': uid(), 'serverSessionHash': 'b' * 64}
        self.opened()
        own = self.service.sessions[self.identity['leaseId']]
        own.bridge = Mock()
        own.state = 'UPDATING'

        def finish_own_stop():
            time.sleep(0.3)
            with self.service.lock:
                own.state = 'READY'

        threading.Thread(target=finish_own_stop).start()
        started = time.monotonic()
        with patch('worker.ROOM_SECONDS', 0.4):
            refused = self.send(self.execution())
        # The other unit never stops, so making room gives up when the shared budget ends.
        self.assertEqual('EXECUTION_CAPACITY', refused['code'])
        self.assertLess(time.monotonic() - started, 0.6)

    def test_a_spent_room_budget_stops_no_other_unit(self):
        self.service.config['maxLiveUnits'] = 1
        self.opened()
        other = self.service.sessions[self.identity['leaseId']]
        other.channel, other.unit, other.bridge = object(), 'other-unit', Mock()
        self.identity = {**self.identity, 'leaseId': uid(), 'serverSessionHash': 'b' * 64}
        self.opened()
        own = self.service.sessions[self.identity['leaseId']]

        # Waiting for its own unit spent the budget: refused, and the other warm unit is kept.
        self.assertFalse(self.service.make_room(own, time.monotonic() - 0.01))
        self.assertFalse(self.backend.stopped)
        self.assertEqual('READY', other.state)
        self.assertIsNotNone(other.channel)

    def test_a_unit_already_stopping_frees_its_place_instead_of_a_refusal(self):
        self.service.config['maxLiveUnits'] = 1
        self.opened()
        first = self.service.sessions[self.identity['leaseId']]
        first.channel, first.unit, first.bridge = object(), 'first-unit', Mock()
        first.state = 'UPDATING'
        self.identity = {**self.identity, 'leaseId': uid(), 'serverSessionHash': 'b' * 64}
        self.opened()
        self.service.sessions[self.identity['leaseId']].bridge = Mock()

        def finish_stopping():
            time.sleep(0.2)
            first.channel = None
            first.state = 'READY'

        threading.Thread(target=finish_stopping).start()
        self.assertTrue(self.send(self.execution())['ok'])

    def test_a_stop_that_outlasts_the_room_budget_refuses_the_command_before_it_runs(self):
        self.service.config['maxLiveUnits'] = 1
        self.opened()
        first = self.service.sessions[self.identity['leaseId']]
        first.channel, first.unit, first.bridge = object(), 'first-unit', Mock()
        first.state = 'UPDATING'
        self.identity = {**self.identity, 'leaseId': uid(), 'serverSessionHash': 'b' * 64}
        self.opened()
        second = self.service.sessions[self.identity['leaseId']]
        second.bridge = Mock()

        with patch('worker.ROOM_SECONDS', 0.2):
            refused = self.send(self.execution())
        self.assertEqual('EXECUTION_CAPACITY', refused['code'])
        self.assertEqual('READY', second.state)
        self.assertIsNone(second.channel)
        first.channel, first.state = None, 'READY'
        self.assertTrue(self.send(self.execution())['ok'])

    def test_a_connection_beyond_capacity_is_closed_unread_and_logged(self):
        from worker import Server
        server = Server.__new__(Server)
        server.capacity = threading.BoundedSemaphore(1)
        server.capacity.acquire()
        request = Mock()
        with self.assertLogs('poketto.worker', 'WARNING') as logged:
            server.process_request(request, None)
            server.process_request(Mock(), None)
        request.close.assert_called_once_with()
        # Refusals in a burst are logged once, not once each.
        self.assertEqual(1, len(logged.output))
        self.assertIn('maxConnections', logged.output[0])

    def test_a_command_result_reports_the_worker_stages_it_waited_in(self):
        self.opened()
        self.service.sessions[self.identity['leaseId']].bridge = Mock()
        result = self.send(self.execution())
        self.assertTrue(result['ok'], result)
        timings = result['result']['timings']
        self.assertEqual({'roomMs', 'lockMs'}, set(timings))
        self.assertTrue(all(isinstance(value, int) and value >= 0 for value in timings.values()))

    def test_an_empty_bridge_poll_waits_a_second_for_a_request(self):
        from worker import BRIDGE_POLL_SECONDS
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.bridge = Mock()
        session.bridge.poll.side_effect = lambda timeout, execution_id: time.sleep(timeout)
        session.state, session.execution_id = 'RUNNING', uid()
        self.assertTrue(self.send(self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))['ok'])
        session.bridge.poll.assert_called_once()
        self.assertEqual(session.execution_id, session.bridge.poll.call_args.kwargs['execution_id'])
        self.assertGreater(session.bridge.poll.call_args.kwargs['timeout'], 0.9)
        self.assertEqual(1.0, BRIDGE_POLL_SECONDS)

    def test_a_poll_sent_while_its_command_waits_for_the_unit_to_stop_is_empty(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.bridge = Mock()
        session.state = 'UPDATING'
        started = time.monotonic()
        answer = self.send(self.payload('BRIDGE_POLL', {'executionId': uid()}))
        # Not SESSION_NOT_READY, which the application cannot tell from a lost command.
        self.assertTrue(answer['ok'], answer)
        self.assertIsNone(answer['bridgeRequest'])
        self.assertLess(time.monotonic() - started, 2)
        session.bridge.poll.assert_not_called()

    def test_empty_polls_wait_when_running_crosses_mailbox_startup_and_cleanup(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id = 'RUNNING', uid()
        with tempfile.TemporaryDirectory() as temporary:
            session.bridge = LeaseBridge(Path(temporary) / 'bridge', os.getgid())
            try:
                for phase in ('before_mailbox_start', 'after_mailbox_cleanup'):
                    with self.subTest(phase=phase):
                        if phase == 'after_mailbox_cleanup':
                            session.bridge.reset_command(session.execution_id)
                            session.bridge.reset_command()
                        # RUNNING covers backend preparation and flush; the mailbox can be empty in both.
                        with patch('worker.BRIDGE_POLL_SECONDS', 0.1):
                            started = time.monotonic()
                            answer = self.send(self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
                            elapsed = time.monotonic() - started
                        self.assertTrue(answer['ok'], answer)
                        self.assertIsNone(answer['bridgeRequest'])
                        self.assertEqual(session.execution_id, answer['executionId'])
                        self.assertGreaterEqual(elapsed, 0.09, 'an empty mailbox must not create a busy poll loop')
                        self.assertFalse(session.cancelled.is_set())
            finally:
                session.bridge.close()

    def test_a_poll_during_mailbox_startup_serves_a_request_without_waiting_the_full_interval(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id = 'RUNNING', uid()
        with tempfile.TemporaryDirectory() as temporary, ThreadPoolExecutor() as pool:
            session.bridge = LeaseBridge(Path(temporary) / 'bridge', os.getgid())
            empty = threading.Event()
            original_poll = session.bridge.poll

            def observed_poll(**kwargs):
                result = original_poll(**kwargs)
                if result is None:
                    empty.set()
                return result

            try:
                with patch.object(session.bridge, 'poll', side_effect=observed_poll):
                    pending = pool.submit(self.send, self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
                    self.assertTrue(empty.wait(2))
                    self.assertTrue(self.send(self.payload('RENEW'))['ok'])
                    session.bridge.reset_command(session.execution_id)
                    request = {'requestId': uid(), 'executionId': session.execution_id,
                               'operation': 'status', 'arguments': {}}
                    _send(session.bridge.path, request, time.monotonic() + 1)
                    answer = pending.result(timeout=0.5)
                    self.assertTrue(answer['ok'], answer)
                    self.assertEqual(request, answer['bridgeRequest'])
            finally:
                session.bridge.close()

    def test_a_retried_empty_poll_does_not_take_the_next_executions_request(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id = 'RUNNING', uid()
        with tempfile.TemporaryDirectory() as temporary, ThreadPoolExecutor() as pool:
            session.bridge = LeaseBridge(Path(temporary) / 'bridge', os.getgid())
            empty = threading.Event()
            original_poll = session.bridge.poll

            def observed_poll(**kwargs):
                result = original_poll(**kwargs)
                if result is None:
                    empty.set()
                return result

            try:
                with patch('worker.BRIDGE_POLL_SECONDS', 0.1), patch.object(
                        session.bridge, 'poll', side_effect=observed_poll):
                    pending = pool.submit(self.send, self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
                    self.assertTrue(empty.wait(2))
                    with self.service.lock:
                        session.execution_id = uid()
                    session.bridge.reset_command(session.execution_id)
                    request = {'requestId': uid(), 'executionId': session.execution_id,
                               'operation': 'status', 'arguments': {}}
                    _send(session.bridge.path, request, time.monotonic() + 1)
                    self.assertIsNone(pending.result(timeout=2)['bridgeRequest'])
                    self.assertFalse(session.bridge.pending)
                    answer = self.send(self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
                    self.assertTrue(answer['ok'], answer)
                    self.assertEqual(request, answer['bridgeRequest'])
            finally:
                session.bridge.close()

    def test_a_poll_that_arrives_before_its_command_starts_waits_for_it(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.bridge = Mock()
        session.bridge.poll.return_value = None
        execution = uid()

        def start():
            time.sleep(0.2)
            with self.service.lock:
                session.state, session.execution_id = 'RUNNING', execution

        threading.Thread(target=start).start()
        self.assertTrue(self.send(self.payload('BRIDGE_POLL', {'executionId': execution}))['ok'])
        self.assertEqual(execution, session.bridge.poll.call_args.kwargs['execution_id'])

    def test_a_late_poll_from_a_finished_command_cannot_take_the_next_request(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        previous = uid()
        late = self.payload('BRIDGE_POLL', {'executionId': previous})
        session.state, session.execution_id = 'RUNNING', uid()
        with tempfile.TemporaryDirectory() as temporary:
            session.bridge = LeaseBridge(Path(temporary) / 'bridge', os.getgid())
            try:
                session.bridge.reset_command(session.execution_id)
                request = {'requestId': uid(), 'executionId': session.execution_id,
                           'operation': 'status', 'arguments': {}}
                _send(session.bridge.path, request, time.monotonic() + 1)
                stale = self.send(late)
                self.assertTrue(stale['ok'], stale)
                self.assertEqual(previous, stale['executionId'])
                self.assertIsNone(stale['bridgeRequest'])
                self.assertFalse(session.bridge.pending)
                current = self.send(self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
                self.assertEqual(request, current['bridgeRequest'])
                self.assertFalse(session.cancelled.is_set())
            finally:
                session.bridge.close()

    def test_each_refusal_reason_is_logged_with_the_refusals_it_counted(self):
        from worker import Server
        server = Server.__new__(Server)
        with self.assertLogs('poketto.worker', 'WARNING') as logged:
            server.refuse(Mock(), 'maxConnections reached')
            server.refuse(Mock(), 'maxConnections reached')
            server.refuse(Mock(), 'maxConnections reached')
            # Another reason inside the same second still gets its own line.
            server.refuse(Mock(), 'no thread could be started')
            server.refusals['maxConnections reached'] = (time.monotonic() - 1, 2)
            server.refuse(Mock(), 'maxConnections reached')
        self.assertEqual([
            'refused 1 connection(s) unread: maxConnections reached',
            'refused 1 connection(s) unread: no thread could be started',
            'refused 3 connection(s) unread: maxConnections reached',
        ], [line.split(':', 2)[2] for line in logged.output])

    def test_idle_configuration_is_explicit_and_bounded(self):
        example = json.loads(Path(__file__).with_name('config.example.json').read_text())
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'worker.json'
            path.write_text(json.dumps(example))
            self.assertEqual(300, load_config(path)['idleUnitSeconds'])
            for value in (0, -1, 86401, True, None, '1800'):
                path.write_text(json.dumps({**example, 'idleUnitSeconds': value}))
                with self.subTest(value=value), self.assertRaises(Rejected):
                    load_config(path)
            del example['idleUnitSeconds']
            path.write_text(json.dumps(example))
            with self.assertRaises(KeyError):
                load_config(path)

    def test_signed_move_transfer_preflight_install_and_release_keep_identity_and_local_edits(self):
        import hashlib
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', uid(), 'owned-unit'
        with tempfile.TemporaryDirectory() as temporary:
            self.backend.root = Path(temporary)
            repository = self.backend.root / 'work/repository'
            (repository / 'private/box').mkdir(parents=True)
            (repository / 'private/box/a.md').write_bytes(b'before')
            (repository / 'private/scratch.md').write_bytes(b'unselected')
            plan = {'operationId': uid(), 'source': 'private/box', 'destination': 'private/new',
                    'originals': {'private/box/a.md': {'sha256': hashlib.sha256(b'before').hexdigest(),
                                                       'bytes': 6, 'optional': False}},
                    'relocations': {'private/box/a.md': 'private/new/a.md'},
                    'replacements': {'private/new/a.md': base64.b64encode(b'after').decode()}}
            raw = json.dumps(plan).encode()
            result = self.send(self.payload('MOVE_BEGIN', {'executionId': session.execution_id,
                'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}))
            self.assertTrue(result['ok'], result)
            reference = {'executionId': session.execution_id, 'transferId': result['transferId']}
            chunk = self.payload('MOVE_CHUNK', {**reference, 'offset': 0, 'data': base64.b64encode(raw).decode()})
            foreign = {**chunk, 'requestId': uid(), 'serverSessionHash': 'f' * 64}
            self.assertEqual('SESSION_NOT_FOUND', self.send(foreign)['code'])
            self.assertEqual('EXECUTION_MISMATCH', self.send(self.payload('MOVE_CHECK', {**reference, 'executionId': uid()}))['code'])
            self.assertTrue(self.send(chunk)['ok'])
            self.assertTrue(self.send(self.payload('MOVE_CHECK', reference))['checked']['ready'])
            self.assertEqual(b'before', (repository / 'private/box/a.md').read_bytes())
            self.assertEqual('MATERIALIZE_NOT_FOUND', self.send(self.payload('MATERIALIZE_ABORT', reference))['code'])
            installed = self.send(self.payload('MOVE_COMMIT', reference))
            self.assertTrue(installed['ok'], installed)
            self.assertEqual(b'after', (repository / 'private/new/a.md').read_bytes())
            self.assertFalse((repository / 'private/box').exists())
            (repository / 'private/new/a.md').write_bytes(b'new edit after acknowledgement')
            self.assertEqual(installed['installed'], self.send(self.payload('MOVE_COMMIT', reference))['installed'])
            self.assertEqual(b'new edit after acknowledgement', (repository / 'private/new/a.md').read_bytes())
            self.assertEqual(b'unselected', (repository / 'private/scratch.md').read_bytes())
            self.assertTrue(self.send(self.payload('MOVE_ABORT', reference))['ok'])
            self.assertIsNone(session.incoming)
            self.assertFalse(list(self.backend.root.glob('incoming-*')))
            session.execution_id = uid()
            retry = self.send(self.payload('MOVE_BEGIN', {'executionId': session.execution_id,
                'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}))
            reference = {'executionId': session.execution_id, 'transferId': retry['transferId']}
            self.assertTrue(self.send(self.payload('MOVE_CHUNK', {**reference, 'offset': 0, 'data': base64.b64encode(raw).decode()}))['ok'])
            repeated = self.send(self.payload('MOVE_COMMIT', reference))
            self.assertEqual(installed['installed'], repeated['installed'])
            self.assertEqual(b'new edit after acknowledgement', (repository / 'private/new/a.md').read_bytes())
            self.assertTrue(self.send(self.payload('MOVE_ABORT', reference))['ok'])
            receipts = list(self.backend.root.glob('move-result-*.json'))
            self.assertEqual(1, len(receipts))
            self.assertEqual(0o600, receipts[0].stat().st_mode & 0o777)

    def test_artifact_frames_bind_identity_and_preserve_bytes_after_command_completion(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', uid(), 'owned-unit'
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            repository = root / 'work/repository'
            repository.mkdir(parents=True)
            (repository / 'note.txt').write_bytes(b'original')
            session.artifacts = ArtifactStore(root)
            try:
                data = {'executionId': session.execution_id, 'path': 'note.txt', 'mediaType': 'text/plain'}
                wrong = self.send(self.payload('ARTIFACT_CREATE', {**data, 'executionId': uid()}))
                self.assertEqual('EXECUTION_MISMATCH', wrong['code'])
                created = self.send(self.payload('ARTIFACT_CREATE', data))
                self.assertTrue(created['ok'], created)
                reference = {'artifactId': created['artifact']['artifactId'], 'offset': 0, 'limit': 65536}
                session.state, session.execution_id, session.unit = 'READY', '', ''
                (repository / 'note.txt').write_bytes(b'later local edit')
                self.assertEqual(b'original', base64.b64decode(self.send(self.payload('ARTIFACT_READ', reference))['data']))
                self.assertEqual('EXECUTION_MISMATCH', self.send(self.payload('ARTIFACT_CREATE', data))['code'])
                for key in ('principalId', 'accountId', 'workspaceId', 'appBootId', 'serverSessionHash', 'leaseId'):
                    foreign = self.payload('ARTIFACT_READ', reference)
                    foreign[key] = 'f' * 64 if key == 'serverSessionHash' else uid()
                    self.assertEqual('SESSION_NOT_FOUND', self.send(foreign)['code'])
                invalid = self.send(self.payload('ARTIFACT_READ', {**reference, 'offset': -1}))
                self.assertEqual('INVALID_ARTIFACT_RANGE', invalid['code'])
                self.assertFalse(session.cancelled.is_set())
                self.assertTrue(self.send(self.payload('ARTIFACT_REMOVE', {'artifactId': reference['artifactId']}))['ok'])
                self.assertEqual('ARTIFACT_UNAVAILABLE', self.send(self.payload('ARTIFACT_READ', reference))['code'])
                self.assertFalse(list(session.artifacts.directory.iterdir()))
            finally:
                session.artifacts.close()

    def test_artifact_expiry_and_revocation_apply_while_the_lease_is_still_present(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        with tempfile.TemporaryDirectory() as temporary:
            ticks = [0]
            session.artifacts = ArtifactStore(temporary, clock=lambda: ticks[0])
            try:
                created = session.artifacts.put('stdout.txt', b'private output')
                reference = {'artifactId': created['artifactId'], 'offset': 0, 'limit': 65536}
                ticks[0] = 300
                self.service.sweep()
                self.assertEqual('ARTIFACT_UNAVAILABLE', self.send(self.payload('ARTIFACT_READ', reference))['code'])
                self.assertFalse(list(session.artifacts.directory.iterdir()))
                retained = session.artifacts.put('stdout.txt', b'other output')
                reference['artifactId'] = retained['artifactId']
                self.assertTrue(self.send(self.payload('REVOKE', {'keyIds': [self.identity['principalId']], 'accountIds': []}))['ok'])
                self.assertEqual('AUTH_REVOKED', self.send(self.payload('ARTIFACT_READ', reference))['code'])
            finally:
                session.artifacts.close()

    def test_hello_advertises_the_codeact_bridge_contract(self):
        response = self.service.hello()
        self.assertEqual(1, response['version'])
        self.assertEqual(2, response['codeActProtocol'])
        self.assertEqual(1, response['artifactProtocol'])
        self.assertEqual(1, response['moveProtocol'])
        self.assertEqual(1, response['exportProtocol'])
        self.assertEqual(1, response['leaseSandboxProtocol'])

    def test_signed_binary_capture_releases_its_protected_file(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', uid(), 'owned-unit'
        with tempfile.TemporaryDirectory() as temporary:
            self.backend.root = Path(temporary)
            repository = self.backend.root / 'work/repository'
            repository.mkdir(parents=True)
            source = bytes(range(256))
            (repository / 'source').write_bytes(source)
            captured = self.send(self.payload('CAPTURE_BINARY', {'executionId': session.execution_id, 'path': 'source'}))
            self.assertTrue(captured['ok'], captured)
            reference = {'executionId': session.execution_id, 'captureId': captured['captureId']}
            (repository / 'source').write_bytes(b'later edit')
            chunk = self.send(self.payload('CAPTURE_READ', {**reference, 'index': 0, 'offset': 0, 'limit': 65536}))
            self.assertEqual(source, base64.b64decode(chunk['data']))
            self.assertTrue(self.send(self.payload('CAPTURE_RELEASE', reference))['ok'])
            self.assertFalse(list(self.backend.root.glob('outgoing-*')))

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

    def test_materialization_io_failures_are_classified_without_losing_local_edits(self):
        import errno
        import hashlib
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id, session.unit = 'RUNNING', uid(), 'owned-unit'
        with tempfile.TemporaryDirectory() as temporary:
            self.backend.root = Path(temporary)
            repository = self.backend.root / 'work/repository'
            repository.mkdir(parents=True)
            (repository / 'retained.md').write_bytes(b'unsaved local edit')
            data = {'executionId': session.execution_id, 'path': 'new.md', 'bytes': 1,
                    'sha256': hashlib.sha256(b'x').hexdigest(), 'expectedSha256': None,
                    'delete': False, 'allowIdentical': False}
            with patch('materialize.os.open', side_effect=OSError(errno.ENOSPC, 'fixture full')):
                failed = self.send(self.payload('MATERIALIZE_BEGIN', data))
            self.assertEqual('MATERIALIZE_CAPACITY', failed['code'])
            self.assertIsNone(session.incoming)
            created = self.send(self.payload('MATERIALIZE_BEGIN', data))
            self.assertTrue(created['ok'], created)
            reference = {'executionId': session.execution_id, 'transferId': created['transferId']}
            with patch('materialize.os.write', side_effect=OSError(errno.ENOSPC, 'fixture full')):
                failed = self.send(self.payload('MATERIALIZE_CHUNK',
                    {**reference, 'offset': 0, 'data': base64.b64encode(b'x').decode()}))
            self.assertEqual('MATERIALIZE_CAPACITY', failed['code'])
            self.assertTrue(self.send(self.payload('MATERIALIZE_ABORT', reference))['ok'])
            created = self.send(self.payload('MATERIALIZE_BEGIN', data))
            reference = {'executionId': session.execution_id, 'transferId': created['transferId']}
            self.assertTrue(self.send(self.payload('MATERIALIZE_CHUNK',
                {**reference, 'offset': 0, 'data': base64.b64encode(b'x').decode()}))['ok'])
            with patch('materialize.os.pread', side_effect=OSError(errno.EIO, 'fixture read failure')):
                failed = self.send(self.payload('MATERIALIZE_COMMIT', reference))
            self.assertEqual('MATERIALIZE_REJECTED', failed['code'])
            self.assertTrue(self.send(self.payload('MATERIALIZE_ABORT', reference))['ok'])
            self.assertFalse(session.cancelled.is_set())
            self.assertEqual(b'unsaved local edit', (repository / 'retained.md').read_bytes())
            self.assertFalse((repository / 'new.md').exists())
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
                session.bridge.reset_command(session.execution_id)
                environment = patch.dict(os.environ, {'POKETTO_EXECUTION_ID': session.execution_id})
                environment.start()
                self.addCleanup(environment.stop)
                client = pool.submit(call, session.bridge.path, 'status', {}, 3)
                polled = self.send(self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
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

    def test_bridge_poll_during_command_input_cleanup_does_not_cancel_the_lease(self):
        self.opened()
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.execution_id = 'RUNNING', uid()
        with tempfile.TemporaryDirectory() as temporary:
            session.bridge = LeaseBridge(Path(temporary) / 'bridge', os.getgid())
            try:
                # reset_command owns reader while draining completed command input.
                with ThreadPoolExecutor(max_workers=1) as pool:
                    with session.bridge.reader:
                        pending = pool.submit(self.send, self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))
                        deadline = time.monotonic() + 1
                        while not pending.done() and not session.cancelled.is_set() and time.monotonic() < deadline:
                            threading.Event().wait(.01)
                    result = pending.result(timeout=2)
                self.assertTrue(result['ok'], result)
                self.assertIsNone(result['bridgeRequest'])
                self.assertFalse(session.cancelled.is_set())
                self.assertEqual('RUNNING', session.state)
                self.assertTrue(self.send(self.payload('BRIDGE_POLL', {'executionId': session.execution_id}))['ok'])
                self.assertFalse(session.bridge.closed)
            finally:
                session.bridge.close()

    def setUp(self):
        self.key = Ed25519PrivateKey.generate()
        self.backend = Backend()
        self.now = 1000
        self.service = Service(self.key.public_key(), self.backend,
            {'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxRequests': 100,
             'idleUnitSeconds': 1800, 'maxSessions': 2, 'maxBundleBytes': 1000, 'maxTimeoutMillis': 60000, 'maxExecutionsPerSession': 1000},
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
                                 'bundleBytes': 80, 'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'})
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
                                 'bundleBytes': 80, 'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'})
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
                                 'bundleBytes': 80, 'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'})
        self.assertEqual('SESSION_CLOSED', self.send(p)['code'])
        self.assertFalse(self.backend.opened)

    def test_account_revocation_before_open_prevents_creation(self):
        self.assertTrue(self.send(self.payload('REVOKE', {'keyIds': [], 'accountIds': [self.identity['accountId']]}))['ok'])
        p = self.payload('OPEN', {'exportId': uid(), 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'})
        self.assertEqual('AUTH_REVOKED', self.send(p)['code'])
        self.assertFalse(self.backend.opened)

    def test_per_session_replay_capacity_is_bounded(self):
        self.service.config['maxExecutionsPerSession'] = 1
        self.opened()
        self.assertTrue(self.send(self.execution())['ok'])
        refused = self.send(self.execution())
        self.assertEqual('EXECUTION_CAPACITY', refused['code'])
        self.assertNotIn('reason', refused)

    def test_a_seeded_open_needs_an_exact_seed_and_names_no_export(self):
        seeded = {'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full', 'seed': True}
        self.assertEqual('SEED_ABSENT', self.send(self.payload('OPEN', seeded))['code'])
        self.assertFalse(self.backend.opened)
        self.assertFalse(self.service.sessions)
        self.backend.seeds = {(self.identity['workspaceId'], 'full', 'c' * 40)}
        for invalid in ({**seeded, 'seed': 1}, {**seeded, 'exportId': uid()}, {**seeded, 'scope': 'other'}):
            self.assertEqual('INVALID_REQUEST', self.send(self.payload('OPEN', invalid))['code'])
        self.assertTrue(self.send(self.payload('OPEN', seeded))['ok'])
        self.assertEqual([self.identity['leaseId']], self.backend.opened)
        self.assertIs(True, self.backend.open_data['seed'])

    def test_hello_advertises_seeded_opens(self):
        self.assertEqual(1, self.service.hello()['seedProtocol'])

    def test_limits_and_no_path_fields(self):
        p = self.payload('OPEN', {'exportId': '../source', 'bundleSha256': 'b' * 64,
                                 'bundleBytes': 80, 'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'})
        self.assertEqual('INVALID_REQUEST', self.send(p)['code'])
        self.opened()
        p = self.execution()
        p['data']['command'] = '\0'
        self.assertEqual('INVALID_REQUEST', self.send(p)['code'])
        p = self.execution()
        p['data']['hostPath'] = '/etc'
        self.assertEqual('INVALID_REQUEST', self.send(p)['code'])
        self.assertFalse(self.backend.executed)


class SandboxPolicyTests(unittest.TestCase):
    def test_every_mode_hides_the_host_sysfs_that_a_denied_root_leaves_readable(self):
        backend = SystemdBackend.__new__(SystemdBackend)
        backend.c = {'toolsRoot': '/opt/poketto/tools'}
        backend.sessions = Path('/run/poketto/sessions')
        session = Session(uid(), (), '', 115)
        for mode in ('initialize', 'shell', 'baseline'):
            with self.subTest(mode=mode):
                files = backend.sandbox_policy(session, {'mode': mode})['filesystem']
                self.assertEqual(['/', '/sys'], files['denyRead'])
                self.assertIn('/proc', files['allowRead'])
                self.assertIn('/dev', files['allowRead'])
                for path in files['allowRead'] + files['allowWrite']:
                    self.assertFalse(path == '/sys' or path.startswith('/sys/'), path)


if __name__ == '__main__':
    unittest.main()
