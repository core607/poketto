import hashlib
import os
from pathlib import Path
import tempfile
import unittest

from materialize import IncomingFile
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
