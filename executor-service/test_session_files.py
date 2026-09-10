import os
from pathlib import Path
import tempfile
import unittest

from session_files import CaptureRejected, MAX_TEXT_BYTES, capture_text, capture_optional, selected_paths


class SelectedFileCaptureTests(unittest.TestCase):
    def test_optional_capture_distinguishes_absence_empty_files_and_invalid_roots(self):
        self.assertEqual(('missing/child.md',), capture_optional(self.root, 'missing/child.md')['absent'])
        (self.repository / 'empty.md').write_bytes(b'')
        self.assertEqual({'empty.md': ''}, capture_optional(self.root, 'empty.md')['writes'])
        (self.repository / 'link').symlink_to(self.root / 'missing')
        with self.assertRaises(CaptureRejected):
            capture_optional(self.root, 'link')
        with self.assertRaises(CaptureRejected):
            capture_optional(self.root / 'not-a-lease', 'note.md')

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name) / 'session'
        self.repository = self.root / 'work/repository'
        self.repository.mkdir(parents=True)

    def test_exact_text_and_explicit_deletions_preserve_unselected_files(self):
        source = '\ufeff---\r\ntitle: 日常\r\n---\r\n正文\n'
        (self.repository / 'selected.md').write_bytes(source.encode())
        (self.repository / 'other.md').write_text('unsaved other change')
        result = capture_text(self.root, ['selected.md'], ['removed.md'])
        self.assertEqual({'writes': {'selected.md': source}, 'deletes': ('removed.md',)}, result)
        self.assertEqual('unsaved other change', (self.repository / 'other.md').read_text())
        with self.assertRaises(CaptureRejected):
            capture_text(self.root, ['missing-media.pdf'], [])

    def test_symlinks_at_each_level_and_special_files_are_rejected(self):
        outside = Path(self.temporary.name) / 'outside'
        outside.mkdir()
        (outside / 'secret.md').write_text('outside sentinel')
        (self.repository / 'linked').symlink_to(outside, target_is_directory=True)
        (self.repository / 'file.md').symlink_to(outside / 'secret.md')
        os.mkfifo(self.repository / 'pipe')
        os.link(outside / 'secret.md', self.repository / 'hard.md')
        for path in ['linked/secret.md', 'file.md', 'pipe', 'hard.md']:
            with self.subTest(path=path), self.assertRaises(CaptureRejected):
                capture_text(self.root, [path], [])
        self.repository.rename(self.repository.with_name('original'))
        self.repository.symlink_to(outside, target_is_directory=True)
        with self.assertRaises(CaptureRejected):
            capture_text(self.root, ['secret.md'], [])

    def test_total_byte_bound_and_utf8_are_enforced_without_partial_result(self):
        (self.repository / 'large.md').write_bytes(b'x' * MAX_TEXT_BYTES)
        (self.repository / 'one.md').write_bytes(b'y')
        self.assertEqual(MAX_TEXT_BYTES, len(capture_text(self.root, ['large.md'], [])['writes']['large.md']))
        with self.assertRaises(CaptureRejected):
            capture_text(self.root, ['large.md', 'one.md'], [])
        (self.repository / 'binary').write_bytes(b'\xff')
        with self.assertRaises(CaptureRejected):
            capture_text(self.root, ['binary'], [])

    def test_paths_collisions_and_explicit_selection_bounds(self):
        for path in ['/etc/passwd', '../escape', 'a/../b', 'a//b', '.git/config', 'x/.GIT/config', 'a\\b', 'bad\0name', 'bad\nname', 'x' * 4097, '\ud800']:
            with self.subTest(path=repr(path)), self.assertRaises(CaptureRejected):
                selected_paths([path], [])
        for writes, deletes in [([], []), (['a'] * 65, []), (['straße'], ['strasse']), (['é'], ['e\u0301']), (['a'], ['a'])]:
            with self.subTest(writes=writes), self.assertRaises(CaptureRejected):
                selected_paths(writes, deletes)


if __name__ == '__main__':
    unittest.main()
