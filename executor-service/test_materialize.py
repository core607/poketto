import hashlib
import errno
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace

from materialize import IncomingFile, LocalMove, MAX_FILE_BYTES
from session_files import CaptureRejected


def digest(data):
    return hashlib.sha256(data).hexdigest()


class MaterializeTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / 'work/repository'
        self.repository.mkdir(parents=True)

    def incoming(self, path, content, expected=None, delete=False):
        result = IncomingFile(self.root, path, len(content), digest(content), expected, delete)
        self.addCleanup(result.close)
        for offset in range(0, len(content), 65536):
            result.append(offset, content[offset:offset + 65536])
        return result

    def install(self, incoming):
        return incoming.install(os.getuid(), os.getgid())

    def test_complete_transfer_preserves_bytes_mode_and_unselected_edits(self):
        path = self.repository / 'note.md'
        path.write_bytes(b'old')
        path.chmod(0o700)
        (self.repository / 'other.md').write_text('unselected')
        content = ('猫\r\n' * 30000).encode()
        incoming = self.incoming('note.md', content, digest(b'old'))
        self.assertEqual(b'old', path.read_bytes())
        result = self.install(incoming)
        self.assertEqual(content, path.read_bytes())
        self.assertEqual(0o700, path.stat().st_mode & 0o777)
        self.assertEqual('unselected', (self.repository / 'other.md').read_text())
        path.write_text('new edit after acknowledgement')
        self.assertEqual(result, self.install(incoming))
        self.assertEqual('new edit after acknowledgement', path.read_text())

    def test_export_size_admission_still_rejects_overflow_and_cleans_incomplete_staging(self):
        # Exercise the protocol ceiling separately from this test container's small tmpfs.
        with patch('materialize.os.statvfs', return_value=SimpleNamespace(f_bavail=1048576, f_frsize=4096)):
            incoming = IncomingFile(self.root, 'large.zip', 128 * 1024 * 1024 + 1, 'a' * 64, None)
        self.assertTrue(incoming.stage.exists())
        with self.assertRaises(CaptureRejected):
            incoming.install(os.getuid(), os.getgid())
        incoming.close()
        self.assertFalse(incoming.stage.exists())
        self.assertFalse((self.repository / 'large.zip').exists())
        with self.assertRaises(CaptureRejected):
            IncomingFile(self.root, 'overflow.zip', MAX_FILE_BYTES + 1, 'a' * 64, None)

    def test_changed_target_and_invalid_chunks_do_not_replace_local_files(self):
        path = self.repository / 'note.md'
        path.write_bytes(b'later edit')
        incoming = self.incoming('note.md', b'remote', digest(b'old'))
        with self.assertRaises(CaptureRejected):
            self.install(incoming)
        self.assertEqual(b'later edit', path.read_bytes())
        partial = IncomingFile(self.root, 'new.md', 4, digest(b'data'), None)
        self.addCleanup(partial.close)
        partial.append(0, b'da')
        with self.assertRaises(CaptureRejected):
            partial.append(0, b'da')
        with self.assertRaises(CaptureRejected):
            self.install(partial)
        self.assertFalse((self.repository / 'new.md').exists())
        partial.append(2, b'XX')
        with self.assertRaises(CaptureRejected):
            self.install(partial)
        corrupted = self.incoming('corrupt.md', b'original')
        os.pwrite(corrupted.fd, b'changed!', 0)
        with self.assertRaises(CaptureRejected):
            self.install(corrupted)
        self.assertFalse((self.repository / 'corrupt.md').exists())

    def test_transfer_admission_keeps_space_for_the_command_bridge(self):
        (self.repository / 'scratch.md').write_bytes(b'unsaved')
        created = []
        try:
            with patch('materialize.os.statvfs', return_value=SimpleNamespace(f_bavail=512, f_frsize=4096)):
                with self.assertRaisesRegex(CaptureRejected, 'Insufficient session space'):
                    created.append(IncomingFile(self.root, 'large.zip', 2 * 1024 * 1024, 'a' * 64, None))
        finally:
            for incoming in created:
                incoming.close()
        self.assertFalse(list(self.root.glob('incoming-*')))
        self.assertEqual(b'unsaved', (self.repository / 'scratch.md').read_bytes())

    def test_disk_exhaustion_during_transfer_discards_only_staging(self):
        path = self.repository / 'note.md'
        path.write_bytes(b'local')
        incoming = IncomingFile(self.root, 'note.md', 1, digest(b'x'), digest(b'local'))
        self.addCleanup(incoming.close)
        with patch('materialize.os.write', side_effect=OSError(errno.ENOSPC, 'fixture full')):
            with self.assertRaisesRegex(CaptureRejected, 'Insufficient session space'):
                incoming.append(0, b'x')
        self.assertFalse(incoming.stage.exists())
        self.assertEqual(b'local', path.read_bytes())

    def test_absence_and_explicit_deletions_have_separate_preconditions(self):
        created = self.incoming('new/folder/empty.md', b'')
        self.install(created)
        path = self.repository / 'new/folder/empty.md'
        self.assertEqual(b'', path.read_bytes())
        with self.assertRaises(CaptureRejected):
            self.install(self.incoming('new/folder/empty.md', b'overwrite'))
        self.install(self.incoming('new/folder/empty.md', b'', digest(b''), delete=True))
        self.assertFalse(path.exists())
        self.install(self.incoming('absent/also-absent.md', b'', delete=True))
        self.assertFalse((self.repository / 'absent').exists())

    def test_symlinks_hardlinks_and_special_files_are_never_followed_or_replaced(self):
        outside = self.root / 'outside'
        outside.mkdir()
        (outside / 'secret').write_bytes(b'secret')
        (self.repository / 'link').symlink_to(outside, target_is_directory=True)
        (self.repository / 'symbolic').symlink_to(outside / 'secret')
        os.link(outside / 'secret', self.repository / 'hard')
        os.mkfifo(self.repository / 'fifo')
        for path in ['link/secret', 'symbolic', 'hard', 'fifo']:
            with self.subTest(path=path), self.assertRaises(CaptureRejected):
                self.install(self.incoming(path, b'replacement', digest(b'secret')))
        self.assertEqual(b'secret', (outside / 'secret').read_bytes())

    def test_fetch_can_reuse_identical_bytes_but_cannot_overwrite_a_different_local_file(self):
        path = self.repository / 'original.pdf'
        path.write_bytes(b'original')
        before = path.stat()
        fetched = IncomingFile(self.root, 'original.pdf', 8, digest(b'original'), None, allow_identical=True)
        self.addCleanup(fetched.close)
        fetched.append(0, b'original')
        self.assertFalse(self.install(fetched)['changed'])
        self.assertEqual(before.st_ino, path.stat().st_ino)
        path.write_bytes(b'local edit')
        again = IncomingFile(self.root, 'original.pdf', 8, digest(b'original'), None, allow_identical=True)
        self.addCleanup(again.close)
        again.append(0, b'original')
        with self.assertRaises(CaptureRejected):
            self.install(again)
        self.assertEqual(b'local edit', path.read_bytes())


class LocalMoveTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.repository = self.root / 'work/repository'
        self.repository.mkdir(parents=True)
        self.before = {'private/box/note.md': b'[ref](../ref.md)',
                       'private/box/raw.bin': bytes(range(256)),
                       'private/ref.md': b'[note](box/note.md)',
                       '.poketto/assets.json': b'before-index'}
        for path, content in self.before.items():
            target = self.repository / path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        (self.repository / 'private/scratch.md').write_bytes(b'unselected local edit')

    def plan(self, destination='private/deeper/box'):
        originals = {path: {'sha256': digest(content), 'bytes': len(content), 'optional': False}
                     for path, content in self.before.items()}
        originals['private/box/paper.pdf'] = {'sha256': digest(b'PDF'), 'bytes': 3, 'optional': True}
        relocations = {path: destination + path[len('private/box'):]
                       for path in originals if path.startswith('private/box/')}
        return LocalMove(self.root, 'private/box', destination, originals, relocations,
                         {destination + '/note.md': b'[ref](../../ref.md)',
                          'private/ref.md': b'[note](deeper/box/note.md)',
                          '.poketto/assets.json': b'after-index'})

    def test_directory_and_references_move_together_without_materializing_missing_media(self):
        raw = self.repository / 'private/box/raw.bin'
        inode = raw.stat().st_ino
        plan = self.plan()
        self.assertIn('private/box/raw.bin', plan.check())
        result = plan.install(os.getuid(), os.getgid())
        self.assertFalse(result['alreadyApplied'])
        self.assertFalse((self.repository / 'private/box').exists())
        self.assertEqual(inode, (self.repository / 'private/deeper/box/raw.bin').stat().st_ino)
        self.assertEqual(self.before['private/box/raw.bin'], (self.repository / 'private/deeper/box/raw.bin').read_bytes())
        self.assertFalse((self.repository / 'private/deeper/box/paper.pdf').exists())
        self.assertEqual(b'[ref](../../ref.md)', (self.repository / 'private/deeper/box/note.md').read_bytes())
        self.assertEqual(b'after-index', (self.repository / '.poketto/assets.json').read_bytes())
        self.assertEqual(b'unselected local edit', (self.repository / 'private/scratch.md').read_bytes())
        self.assertTrue(self.plan().install(os.getuid(), os.getgid())['alreadyApplied'])
        self.assertEqual([], list(self.root.glob('move-install-*')))
        self.assertTrue(all(p.stat().st_nlink == 1 for p in self.repository.rglob('*') if p.is_file()))

    def test_edits_after_preflight_and_untracked_sources_are_preserved(self):
        plan = self.plan()
        plan.check()
        note = self.repository / 'private/box/note.md'
        note.write_bytes(b'edited after preflight')
        with self.assertRaises(CaptureRejected):
            plan.install(os.getuid(), os.getgid())
        self.assertEqual(b'edited after preflight', note.read_bytes())
        note.write_bytes(self.before['private/box/note.md'])
        extra = self.repository / 'private/box/untracked'
        extra.write_bytes(b'keep')
        with self.assertRaises(CaptureRejected):
            plan.check()
        self.assertEqual(b'keep', extra.read_bytes())
        extra.unlink()
        extra.mkdir()
        with self.assertRaises(CaptureRejected):
            plan.check()
        self.assertTrue(extra.is_dir())
        self.assertFalse((self.repository / 'private/deeper').exists())

    def test_installation_fault_restores_directory_and_original_references(self):
        replace = os.replace
        failed = False

        def fail_once(source, destination, **kwargs):
            nonlocal failed
            if Path(destination) == self.repository / '.poketto/assets.json' and not failed:
                failed = True
                raise OSError('injected installation fault')
            return replace(source, destination, **kwargs)

        with patch('materialize.os.replace', side_effect=fail_once), self.assertRaises(OSError):
            self.plan().install(os.getuid(), os.getgid())
        self.assertTrue(failed)
        for path, content in self.before.items():
            self.assertEqual(content, (self.repository / path).read_bytes())
            self.assertEqual(1, (self.repository / path).stat().st_nlink)
        self.assertFalse((self.repository / 'private/deeper').exists())
        self.assertEqual([], list(self.root.glob('move-install-*')))

    def test_unsafe_entries_and_occupied_destination_cannot_replace_or_read_other_files(self):
        outside = self.root / 'outside'
        outside.write_bytes(b'outside')
        unsafe = self.repository / 'private/box/unsafe'
        unsafe.symlink_to(outside)
        with self.assertRaises((CaptureRejected, OSError)):
            self.plan().install(os.getuid(), os.getgid())
        unsafe.unlink()
        os.mkfifo(unsafe)
        with self.assertRaises(CaptureRejected):
            self.plan().check()
        unsafe.unlink()
        occupied = self.repository / 'private/deeper/box'
        occupied.mkdir(parents=True)
        with self.assertRaises(CaptureRejected):
            self.plan().check()
        self.assertEqual(b'outside', outside.read_bytes())

    def test_case_only_directory_rename_is_supported(self):
        self.plan('private/BOX').install(os.getuid(), os.getgid())
        self.assertFalse((self.repository / 'private/box').exists())
        self.assertEqual(self.before['private/box/raw.bin'], (self.repository / 'private/BOX/raw.bin').read_bytes())

    def test_materialized_optional_media_moves_without_copying_its_inode(self):
        media = self.repository / 'private/box/paper.pdf'
        media.write_bytes(b'PDF')
        inode = media.stat().st_ino
        self.plan().install(os.getuid(), os.getgid())
        moved = self.repository / 'private/deeper/box/paper.pdf'
        self.assertEqual(b'PDF', moved.read_bytes())
        self.assertEqual(inode, moved.stat().st_ino)
        self.assertEqual(1, moved.stat().st_nlink)

    def test_failed_rollback_retains_protected_recovery_files_and_requires_lease_close(self):
        replace = os.replace

        def fail_install_and_rollback(source, destination, **kwargs):
            if Path(destination) in (self.repository / '.poketto/assets.json', self.repository / 'private/box'):
                raise OSError('injected installation and rollback fault')
            return replace(source, destination, **kwargs)

        with patch('materialize.os.replace', side_effect=fail_install_and_rollback):
            with self.assertRaisesRegex(RuntimeError, 'close the lease'):
                self.plan().install(os.getuid(), os.getgid())
        retained = list(self.root.glob('move-install-*'))
        self.assertEqual(1, len(retained))
        self.assertEqual(0o700, retained[0].stat().st_mode & 0o777)
        self.assertTrue(any(p.read_bytes() == self.before['private/box/note.md'] for p in retained[0].iterdir()))
        self.assertEqual(b'unselected local edit', (self.repository / 'private/scratch.md').read_bytes())
