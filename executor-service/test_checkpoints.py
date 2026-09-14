import io
import os
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import patch
import uuid

from checkpoint_tree import CheckpointError, ENTRY, read_tree
from checkpoints import CheckpointStore
from worker import SystemdBackend


class CheckpointTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'session'
        self.repository = self.source / 'work/repository'
        self.repository.mkdir(parents=True)
        (self.source / 'snapshot.bundle').write_bytes(b'trusted original Git bundle')
        (self.repository / '.git').mkdir()
        (self.repository / '.git/HEAD').write_bytes(b'command-controlled Git head')
        (self.repository / 'note.md').write_text('中文 retained text', encoding='utf-8')
        self.owner = tuple(str(uuid.uuid4()) for _ in range(3))
        self.now = 1000
        self.limits = {'maxCheckpointBytes': 128 * 1024, 'maxRetainedBytes': 512 * 1024,
                       'maxCheckpointEntries': 100, 'maxCheckpoints': 8,
                       'minimumFreeBytes': 0, 'retentionSeconds': 3600}
        self.store = self.reopen()

    def reopen(self):
        return CheckpointStore(self.root / 'retained', self.limits, lambda: self.now)

    def metadata(self):
        return {'format': 1, 'checkpointId': str(uuid.uuid4()),
                **dict(zip(('principalId', 'accountId', 'workspaceId'), self.owner)),
                'sourceLeaseId': str(uuid.uuid4()), 'commit': 'a' * 40,
                'scope': 'full', 'expiresAt': (self.now + 300) * 1000}

    def capture(self):
        return self.store.capture(self.source, self.metadata())

    def target(self):
        target = self.root / str(uuid.uuid4())
        target.mkdir(mode=0o700)
        return target

    def restore(self, reference, target=None, owner=None, commit='a' * 40, scope='full'):
        target = target or self.target()
        self.reopen().restore(owner or self.owner, reference, target, commit, scope, os.getuid(), os.getgid())
        return target

    def test_restart_restores_binary_untracked_modes_symlinks_and_independent_original_bundle(self):
        (self.repository / 'untracked.bin').write_bytes(bytes(range(256)) * 10)
        (self.repository / 'executable').write_bytes(b'#!/bin/sh\nexit 0\n')
        (self.repository / 'executable').chmod(0o751)
        (self.repository / 'outside-link').symlink_to('/etc/shadow')
        reference = self.capture()
        (self.repository / 'note.md').write_text('later unacknowledged mutation')
        target = self.restore(reference)
        restored = target / 'work/repository'
        self.assertEqual('中文 retained text', (restored / 'note.md').read_text(encoding='utf-8'))
        self.assertEqual(bytes(range(256)) * 10, (restored / 'untracked.bin').read_bytes())
        self.assertEqual(0o751, stat.S_IMODE((restored / 'executable').stat().st_mode))
        self.assertEqual('/etc/shadow', os.readlink(restored / 'outside-link'))
        self.assertEqual(b'trusted original Git bundle', (target / 'snapshot.bundle').read_bytes())
        self.assertEqual(b'command-controlled Git head', (restored / '.git/HEAD').read_bytes())
        self.assertEqual(0o440, stat.S_IMODE((target / 'snapshot.bundle').stat().st_mode))

    def test_wrong_subject_workspace_scope_and_commit_never_touch_target(self):
        reference = self.capture()
        target = self.target()
        for index in range(3):
            owner = list(self.owner)
            owner[index] = str(uuid.uuid4())
            with self.assertRaises(CheckpointError) as raised:
                self.restore(reference, target, owner)
            self.assertEqual('MISSING', raised.exception.reason)
        for options in ({'commit': 'b' * 40}, {'scope': 'public'}):
            with self.assertRaises(CheckpointError) as raised:
                self.restore(reference, target, **options)
            self.assertEqual('SCOPE_MISMATCH', raised.exception.reason)
        self.assertEqual([], list(target.iterdir()))

    def test_checksum_failure_is_detected_before_any_file_is_restored(self):
        reference = self.capture()
        file = self.store.root / self.store.filename(self.owner, reference['checkpointId'])
        raw = bytearray(file.read_bytes())
        raw[-1] ^= 1
        file.write_bytes(raw)
        target = self.target()
        with self.assertRaises(CheckpointError) as raised:
            self.restore(reference, target)
        self.assertEqual('CORRUPT', raised.exception.reason)
        self.assertEqual([], list(target.iterdir()))

    def test_new_checkpoint_quota_failure_preserves_last_published_bytes(self):
        reference = self.capture()
        (self.repository / 'large').write_bytes(b'x' * self.limits['maxCheckpointBytes'])
        with self.assertRaises(CheckpointError) as raised:
            self.capture()
        self.assertEqual('CAPACITY', raised.exception.reason)
        self.assertEqual(b'trusted original Git bundle', (self.restore(reference) / 'snapshot.bundle').read_bytes())
        self.assertFalse(list(self.store.root.glob('.pending-*')))
        self.assertEqual(1, len(list(self.store.root.glob('*.checkpoint'))))

    def test_aggregate_quota_includes_previous_checkpoint_during_next_capture(self):
        reference = self.capture()
        self.limits['maxRetainedBytes'] = reference['bytes'] * 2 - 1
        with self.assertRaises(CheckpointError) as raised:
            self.capture()
        self.assertEqual('CAPACITY', raised.exception.reason)
        self.restore(reference)

    def test_hardlink_fifo_and_directory_entry_limit_refuse_snapshot_without_harming_previous(self):
        reference = self.capture()
        invalid = self.repository / 'invalid'
        for create in (lambda: os.link(self.repository / 'note.md', invalid), lambda: os.mkfifo(invalid)):
            create()
            with self.assertRaises(CheckpointError) as raised:
                self.capture()
            self.assertEqual('UNSUPPORTED_FILE', raised.exception.reason)
            invalid.unlink()
        self.limits['maxCheckpointEntries'] = 2
        with self.assertRaises(CheckpointError) as raised:
            self.capture()
        self.assertEqual('ENTRY_LIMIT', raised.exception.reason)
        self.limits['maxCheckpointEntries'] = 100
        self.restore(reference)

    def test_expired_checkpoint_is_refused_and_explicit_discard_still_works(self):
        reference = self.capture()
        self.now += 301
        with self.assertRaises(CheckpointError) as raised:
            self.restore(reference)
        self.assertEqual('EXPIRED', raised.exception.reason)
        self.store.discard(self.owner, reference)
        self.assertEqual([], list(self.store.root.glob('*.checkpoint')))

    def test_private_storage_rejects_aliases_and_concurrent_writer(self):
        reference = self.capture()
        with self.store.locked(), self.assertRaises(CheckpointError) as raised:
            self.reopen().inspect(self.owner, reference)
        self.assertEqual('BUSY', raised.exception.reason)
        alias = self.root / 'alias'
        alias.symlink_to(self.store.root, target_is_directory=True)
        with self.assertRaises(ValueError):
            CheckpointStore(alias, self.limits)
        file = self.store.root / self.store.filename(self.owner, reference['checkpointId'])
        os.link(file, self.root / 'hardlink')
        with self.assertRaises(CheckpointError) as raised:
            self.restore(reference)
        self.assertEqual('UNAVAILABLE', raised.exception.reason)

    def test_directory_fsync_failure_does_not_acknowledge_published_checkpoint(self):
        original = os.fsync
        metadata = self.metadata()
        def fail_directory(fd):
            if stat.S_ISDIR(os.fstat(fd).st_mode):
                raise OSError('synthetic disk failure')
            original(fd)
        with patch('checkpoints.os.fsync', fail_directory), self.assertRaises(CheckpointError) as raised:
            self.store.capture(self.source, metadata)
        self.assertEqual('UNCERTAIN', raised.exception.reason)
        # Publication may have happened, but no recoverability acknowledgement was returned.
        self.assertEqual(1, len(list(self.store.root.glob('*.checkpoint'))))

    def test_malicious_paths_and_symlink_parents_cannot_escape_empty_restore_target(self):
        outside = self.root / 'outside'
        outside.mkdir()
        def frame(kind, path, data=b''):
            raw = path.encode()
            return ENTRY.pack(kind, len(raw), 0o700 if kind != 3 else 0, len(data)) + raw + data
        for raw in (frame(2, '../escape', b'bad'), frame(2, '/escape', b'bad'),
                    frame(1, 'work') + frame(3, 'work/link', str(outside).encode()) +
                    frame(2, 'work/link/escape', b'bad')):
            with self.subTest(payload=raw), self.assertRaises((CheckpointError, OSError)):
                read_tree(io.BytesIO(raw), self.target(), 100, 10000, os.getuid(), os.getgid())
        self.assertEqual([], list(outside.iterdir()))

    def test_cancelled_capture_never_replaces_or_deletes_last_acknowledged_checkpoint(self):
        reference = self.capture()
        with self.assertRaises(CheckpointError) as raised:
            self.store.capture(self.source, self.metadata(), lambda: True)
        self.assertEqual('CANCELLED', raised.exception.reason)
        self.restore(reference)
        self.assertFalse(list(self.store.root.glob('.pending-*')))

    def test_missing_repository_cannot_publish_a_checkpoint_that_restore_would_refuse(self):
        reference = self.capture()
        self.repository.rename(self.source / 'work/renamed')
        with self.assertRaises(CheckpointError) as raised:
            self.capture()
        self.assertEqual('UNAVAILABLE', raised.exception.reason)
        self.restore(reference)
        self.assertEqual(1, len(list(self.store.root.glob('*.checkpoint'))))

    def test_expiry_reclaims_full_capacity_and_dead_pending_files_without_removing_live_work(self):
        self.limits['maxCheckpoints'] = 2
        expired = self.capture()
        self.now += 1
        live = self.capture()
        with self.assertRaises(CheckpointError) as full:
            self.capture()
        self.assertEqual('CAPACITY', full.exception.reason)
        orphan = self.store.root / ('.pending-' + str(uuid.uuid4()))
        orphan.write_bytes(b'unpublished capture')
        orphan.chmod(0o600)
        self.now += 299
        self.assertEqual(2, self.store.collect_expired())
        self.assertFalse(orphan.exists())
        with self.assertRaises(CheckpointError) as missing:
            self.restore(expired)
        self.assertEqual('MISSING', missing.exception.reason)
        restored = self.restore(live)
        self.assertEqual('中文 retained text', (restored / 'work/repository/note.md').read_text())
        self.restore(self.capture())

    def test_collection_does_not_race_a_held_capture_or_restore_lock(self):
        self.capture()
        self.now += 300
        with self.store.locked(), self.assertRaises(CheckpointError) as busy:
            self.store.collect_expired()
        self.assertEqual('BUSY', busy.exception.reason)
        self.assertEqual(1, len(list(self.store.root.glob('*.checkpoint'))))
        self.assertEqual(1, self.store.collect_expired())

    def test_collection_refuses_invalid_headers_and_aliases_without_deleting_the_target(self):
        reference = self.capture()
        file = self.store.root / self.store.filename(self.owner, reference['checkpointId'])
        original = file.read_bytes()
        file.write_bytes(b'corrupt!' + original[8:])
        self.now += 300
        with self.assertRaises(CheckpointError) as corrupt:
            self.store.collect_expired()
        self.assertEqual('CORRUPT', corrupt.exception.reason)
        self.assertTrue(file.exists())
        file.write_bytes(original)
        outside = self.root / 'outside'
        os.link(file, outside)
        with self.assertRaises(CheckpointError) as linked:
            self.store.collect_expired()
        self.assertEqual('UNAVAILABLE', linked.exception.reason)
        self.assertEqual(original, outside.read_bytes())
        file.unlink()
        file.symlink_to(outside)
        with self.assertRaises(CheckpointError) as linked:
            self.store.collect_expired()
        self.assertEqual('UNAVAILABLE', linked.exception.reason)
        self.assertEqual(original, outside.read_bytes())

    def test_backend_periodic_collection_defers_busy_store_and_retries_without_client_requests(self):
        self.capture()
        self.now += 300
        backend = SystemdBackend.__new__(SystemdBackend)
        backend.checkpoints = self.store
        backend.next_checkpoint_collection = 0
        with patch('worker.time.monotonic', return_value=10), self.store.locked():
            backend.collect_checkpoints()
        self.assertEqual(1, len(list(self.store.root.glob('*.checkpoint'))))
        with patch('worker.time.monotonic', return_value=69):
            backend.collect_checkpoints()
        self.assertEqual(1, len(list(self.store.root.glob('*.checkpoint'))))
        with patch('worker.time.monotonic', return_value=70):
            backend.collect_checkpoints()
        self.assertEqual([], list(self.store.root.glob('*.checkpoint')))
