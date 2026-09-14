import os
from contextlib import contextmanager
from pathlib import Path
import tempfile
import threading
import unittest
import uuid

from checkpoints import CheckpointStore
from worker import SystemdBackend
import test_worker as fixtures


class StorageBackend(fixtures.Backend):
    checkpoint = SystemdBackend.checkpoint
    active_checkpoint = SystemdBackend.active_checkpoint
    retain_checkpoint = SystemdBackend.retain_checkpoint
    checkpoint_metadata = SystemdBackend.checkpoint_metadata
    checkpoint_call = staticmethod(SystemdBackend.checkpoint_call)
    identity_owner = staticmethod(SystemdBackend.identity_owner)
    discard_checkpoint = SystemdBackend.discard_checkpoint

    def __init__(self, root, config, clock):
        super().__init__()
        self.root = root
        self.checkpoints = CheckpointStore(root / 'retained', config, clock)
        self.frozen_count = 0

    @contextmanager
    def frozen(self, session):
        self.frozen_count += 1
        yield

    def mount_path(self, session):
        return self.root / session.id

    def open(self, session, data):
        super().open(session, data)
        root = self.mount_path(session)
        (root / 'work/repository').mkdir(parents=True)
        (root / 'snapshot.bundle').write_bytes(b'trusted bundle')

    def restore(self, session, data):
        root = self.mount_path(session)
        root.mkdir()
        self.checkpoint_call(lambda: self.checkpoints.restore(session.identity[:3], data, root,
                             session.commit, data['scope'], os.getuid(), os.getgid()))


class CheckpointProtocolTests(unittest.TestCase):
    payload = fixtures.ProtocolTests.payload
    envelope = fixtures.ProtocolTests.envelope
    send = fixtures.ProtocolTests.send
    opened = fixtures.ProtocolTests.opened
    execution = fixtures.ProtocolTests.execution

    def test_active_checkpoint_preserves_running_work_without_claiming_command_completion(self):
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.unit, session.execution_id = 'RUNNING', 'owned-unit', fixtures.uid()
        (self.root / session.id / 'work/repository/inside-command').write_bytes(b'not yet acknowledged')
        data = {'checkpointId': fixtures.uid(), 'expiresAt': 1300000,
                'scope': 'full', 'executionId': session.execution_id}
        with session.operation:
            rejected = self.send(self.payload('CHECKPOINT_ACTIVE', {**data, 'executionId': fixtures.uid()}))
            self.assertEqual('EXECUTION_MISMATCH', rejected['code'])
            answer = self.send(self.payload('CHECKPOINT_ACTIVE', data))
            self.assertTrue(answer['ok'], answer)
            self.assertEqual('RUNNING', answer['state'])
            self.assertEqual(session.execution_id, answer['executionId'])
            self.assertFalse(session.cancelled.is_set())
        self.assertEqual(1, self.backend.frozen_count)
        reference = {key: answer['checkpoint'][key] for key in ('checkpointId', 'sha256', 'bytes')}
        resumed = self.recovery(reference)
        self.assertTrue(self.send(resumed)['ok'])
        self.assertEqual(b'not yet acknowledged',
                         (self.root / resumed['leaseId'] / 'work/repository/inside-command').read_bytes())

    def test_active_checkpoint_cannot_overlap_materialization_or_run_outside_its_command(self):
        session = self.service.sessions[self.identity['leaseId']]
        data = {'checkpointId': fixtures.uid(), 'expiresAt': 1300000,
                'scope': 'full', 'executionId': fixtures.uid()}
        self.assertEqual('EXECUTION_MISMATCH', self.send(self.payload('CHECKPOINT_ACTIVE', data))['code'])
        session.state, session.unit, session.execution_id = 'RUNNING', 'owned-unit', data['executionId']
        with session.files_lock:
            self.assertEqual('SESSION_BUSY', self.send(self.payload('CHECKPOINT_ACTIVE', data))['code'])
        self.assertEqual(0, self.backend.frozen_count)

    def test_revocation_after_active_persistence_prevents_acknowledgement(self):
        session = self.service.sessions[self.identity['leaseId']]
        session.state, session.unit, session.execution_id = 'RUNNING', 'owned-unit', fixtures.uid()
        persisted, release = threading.Event(), threading.Event()
        original = self.backend.active_checkpoint
        def pause_after_persistence(lease, data):
            result = original(lease, data)
            persisted.set()
            release.wait(3)
            return result
        self.backend.active_checkpoint = pause_after_persistence
        results = []
        data = {'checkpointId': fixtures.uid(), 'expiresAt': 1300000,
                'scope': 'full', 'executionId': session.execution_id}
        with session.operation:
            thread = threading.Thread(target=lambda: results.append(self.send(self.payload('CHECKPOINT_ACTIVE', data))))
            thread.start()
            self.addCleanup(thread.join, 4)
            self.addCleanup(release.set)
            self.assertTrue(persisted.wait(1))
            revoked = self.send(self.payload('REVOKE', {'keyIds': [self.identity['principalId']], 'accountIds': []}))
            self.assertTrue(revoked['ok'])
            release.set()
            thread.join(2)
        self.assertEqual('AUTH_REVOKED', results[0]['code'])
        self.assertNotIn('checkpoint', results[0])
        self.assertFalse(session.files_lock.locked())

    def setUp(self):
        fixtures.ProtocolTests.setUp(self)
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.service.config.update({'checkpointRoot': str(self.root / 'retained'),
            'maxCheckpointBytes': 65536, 'maxRetainedBytes': 262144, 'maxCheckpointEntries': 100,
            'maxCheckpoints': 8, 'minimumFreeBytes': 0, 'retentionSeconds': 3600})
        self.backend = StorageBackend(self.root, self.service.config, lambda: self.now)
        self.service.backend = self.backend
        self.opened()

    def checkpoint(self):
        answer = self.send(self.payload('CHECKPOINT', {'checkpointId': fixtures.uid(),
            'expiresAt': (self.now + 300) * 1000, 'scope': 'full'}))
        self.assertTrue(answer['ok'], answer)
        self.assertEqual('READY', answer['state'])
        return {key: answer['checkpoint'][key] for key in ('checkpointId', 'sha256', 'bytes')}

    def recovery(self, reference):
        request = self.payload('RESTORE', {**reference, 'scope': 'full', 'commit': 'c' * 40,
                               'previousLeaseId': self.identity['leaseId']})
        request.update({'leaseId': fixtures.uid(), 'appBootId': fixtures.uid(), 'serverSessionHash': 'b' * 64})
        return request

    def test_signed_restore_across_app_and_transport_boots_stops_original_lease_first(self):
        old = self.identity['leaseId']
        source = self.root / old / 'work/repository'
        (source / 'draft.bin').write_bytes(b'\0retained\xff')
        reference = self.checkpoint()
        request = self.recovery(reference)
        answer = self.send(request)
        self.assertTrue(answer['ok'], answer)
        self.assertEqual('READY', answer['state'])
        self.assertIn(old, self.backend.closed)
        self.assertEqual(b'\0retained\xff', (self.root / request['leaseId'] / 'work/repository/draft.bin').read_bytes())
        self.assertFalse(self.send(self.execution())['ok'])
        self.assertEqual([], self.backend.executed)
        second = self.send(self.recovery(reference))
        self.assertEqual('SESSION_EXISTS', second['code'])
        self.assertEqual('READY', self.service.sessions[request['leaseId']].state)

    def test_recovery_fences_a_restored_writer_with_no_new_completed_checkpoint(self):
        reference = self.checkpoint()
        first = self.recovery(reference)
        self.assertTrue(self.send(first)['ok'])
        next_request = self.recovery(reference)
        next_request['data']['previousLeaseId'] = first['leaseId']
        self.assertTrue(self.send(next_request)['ok'])
        self.assertIn(first['leaseId'], self.backend.closed)
        self.assertEqual('CLOSED', self.service.sessions[first['leaseId']].state)
        self.assertEqual('READY', self.service.sessions[next_request['leaseId']].state)

    def test_fenced_lease_cannot_reopen_after_its_in_memory_session_is_swept(self):
        reference = self.checkpoint()
        self.now += 4
        self.assertTrue(self.send(self.recovery(reference))['ok'])
        self.now = 1018
        self.service.sweep()
        self.assertNotIn(self.identity['leaseId'], self.service.sessions)
        reopened = self.send(self.payload('OPEN', {'exportId': fixtures.uid(), 'bundleSha256': 'b' * 64,
            'bundleBytes': 80, 'commit': 'c' * 40, 'copyId': str(uuid.uuid4()), 'scope': 'full'}))
        self.assertEqual('SESSION_CLOSED', reopened['code'])

    def test_running_source_is_cancelled_but_no_new_writer_is_admitted_until_contained(self):
        reference = self.checkpoint()
        self.backend.wait = threading.Event()
        thread = threading.Thread(target=lambda: self.send(self.execution()))
        thread.start()
        self.addCleanup(thread.join, 4)
        self.addCleanup(self.backend.wait.set)
        self.assertTrue(self.backend.entered.wait(1))
        request = self.recovery(reference)
        answer = self.send(request)
        self.assertEqual('SESSION_BUSY', answer['code'])
        self.assertNotIn(request['leaseId'], self.service.sessions)
        self.backend.wait.set()
        thread.join(2)
        request['requestId'] = fixtures.uid()
        self.assertTrue(self.send(request)['ok'])

    def test_checkpoint_cannot_capture_running_command_or_wrong_transport(self):
        session = self.service.sessions[self.identity['leaseId']]
        data = {'checkpointId': fixtures.uid(), 'expiresAt': 1300000, 'scope': 'full'}
        session.state = 'RUNNING'
        self.assertEqual('SESSION_BUSY', self.send(self.payload('CHECKPOINT', data))['code'])
        session.state = 'READY'
        request = self.payload('CHECKPOINT', data)
        request['serverSessionHash'] = 'f' * 64
        self.assertEqual('SESSION_NOT_FOUND', self.send(request)['code'])
        self.assertFalse(list((self.root / 'retained').glob('*.checkpoint')))

    def test_wrong_owner_and_revocation_prevent_restore_and_discard(self):
        reference = self.checkpoint()
        request = self.recovery(reference)
        request['workspaceId'] = fixtures.uid()
        answer = self.send(request)
        self.assertEqual('MISSING', answer['reason'])
        self.assertEqual([], self.backend.closed)
        self.send(self.payload('REVOKE', {'keyIds': [self.identity['principalId']], 'accountIds': []}))
        self.assertEqual('AUTH_REVOKED', self.send(self.recovery(reference))['code'])
        self.assertEqual('AUTH_REVOKED', self.send(self.payload('CHECKPOINT_REMOVE', reference))['code'])
        self.assertEqual(1, len(list((self.root / 'retained').glob('*.checkpoint'))))

    def test_failed_checkpoint_preserves_ready_lease_and_previous_recovery_point(self):
        reference = self.checkpoint()
        (self.root / self.identity['leaseId'] / 'work/repository/large').write_bytes(b'x' * 65536)
        answer = self.send(self.payload('CHECKPOINT', {'checkpointId': fixtures.uid(),
            'expiresAt': 1300000, 'scope': 'full'}))
        self.assertEqual('CAPACITY', answer['reason'])
        self.assertEqual('READY', self.service.sessions[self.identity['leaseId']].state)
        self.assertEqual([], self.backend.closed)
        self.assertTrue(self.send(self.recovery(reference))['ok'])

    def test_slow_checkpoint_verification_does_not_block_renewal_and_rechecks_revocation(self):
        reference = self.checkpoint()
        entered, release = threading.Event(), threading.Event()
        original = self.backend.checkpoint_metadata
        def slow(request):
            entered.set()
            release.wait(3)
            return original(request)
        self.backend.checkpoint_metadata = slow
        results = []
        thread = threading.Thread(target=lambda: results.append(self.send(self.recovery(reference))))
        thread.start()
        self.addCleanup(thread.join, 4)
        self.addCleanup(release.set)
        self.assertTrue(entered.wait(1))
        self.now += 1
        self.assertTrue(self.send(self.payload('RENEW'))['ok'])
        self.assertTrue(thread.is_alive())
        self.send(self.payload('REVOKE', {'keyIds': [self.identity['principalId']], 'accountIds': []}))
        release.set()
        thread.join(2)
        self.assertEqual('AUTH_REVOKED', results[0]['code'])
