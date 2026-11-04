"""Exercise the guest's OCI changeset reader on a disposable Linux directory."""
import importlib.util
import io
import os
from pathlib import Path
import stat
import tarfile
import tempfile
import unittest


def restore_module():
    local = Path(__file__).resolve().parent
    resource = local / 'restore-upper.py'
    if not resource.is_file():
        resource = local.parent / 'src/main/resources/io/github/core607/poketto/executor/restore-upper.py'
    spec = importlib.util.spec_from_file_location('poketto_restore_upper', resource)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


restore = restore_module()


def changes(entries):
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode='w', format=tarfile.GNU_FORMAT) as archive:
        for name, kind, value in entries:
            item = tarfile.TarInfo(name)
            item.uid, item.gid = os.geteuid(), os.getegid()
            item.mode, item.mtime = 0o751, 12345
            item.type = kind
            if kind == tarfile.REGTYPE:
                item.size = len(value)
                archive.addfile(item, io.BytesIO(value))
            else:
                item.linkname = value
                archive.addfile(item)
    data.seek(0)
    return data


class OverlayRestore(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.base = Path(self.temporary.name, 'workspace')
        self.base.mkdir()
        self.outside = self.base.parent / 'sentinel'
        self.outside.write_text('outside')
        self.root = os.open(self.base, restore.DIRECTORY)
        self.addCleanup(os.close, self.root)

    def apply(self, entries, maximum=4096, count=64):
        restore.apply(self.root, changes(entries), maximum, count)

    def test_opaque_then_additions_restores_bytes_links_modes_and_times(self):
        directory = self.base / 'articles'
        directory.mkdir()
        (directory / 'old').write_text('opaque hides this')
        (self.base / 'removed').write_text('whiteout hides this')
        self.apply([('articles', tarfile.DIRTYPE, ''), ('articles/.wh..wh..opq', tarfile.REGTYPE, b''),
                    ('.wh.removed', tarfile.REGTYPE, b''), ('articles/new', tarfile.REGTYPE, b'new bytes'),
                    ('hard', tarfile.LNKTYPE, 'articles/new'), ('symbolic', tarfile.SYMTYPE, 'articles/new')])
        file = directory / 'new'
        self.assertEqual(file.read_bytes(), b'new bytes')
        self.assertEqual(file.stat().st_ino, (self.base / 'hard').stat().st_ino)
        self.assertEqual(os.readlink(self.base / 'symbolic'), 'articles/new')
        self.assertEqual((stat.S_IMODE(file.stat().st_mode), int(file.stat().st_mtime)), (0o751, 12345))
        self.assertEqual(int(directory.stat().st_mtime), 12345)
        self.assertFalse((directory / 'old').exists())
        self.assertFalse((self.base / 'removed').exists())

    def test_whiteouts_and_type_changes_never_descend_through_base_symlinks(self):
        (self.base / 'linked').symlink_to(self.base.parent)
        self.apply([('linked/.wh.sentinel', tarfile.REGTYPE, b''), ('linked', tarfile.DIRTYPE, ''),
                    ('linked/index', tarfile.REGTYPE, b'replacement')])
        self.assertEqual(self.outside.read_text(), 'outside')
        self.assertFalse((self.base / 'linked').is_symlink())
        self.assertEqual((self.base / 'linked/index').read_bytes(), b'replacement')

    def test_rejects_traversal_special_nodes_and_external_hardlinks(self):
        for entry in [('../sentinel', tarfile.REGTYPE, b'x'), ('/escape', tarfile.REGTYPE, b'x'),
                      ('.wh...', tarfile.REGTYPE, b''), ('outside', tarfile.LNKTYPE, '../sentinel'),
                      ('hard', tarfile.LNKTYPE, 'base-only'), ('device', tarfile.CHRTYPE, ''),
                      ('pipe', tarfile.FIFOTYPE, '')]:
            with self.subTest(entry=entry), self.assertRaises(ValueError):
                self.apply([entry])
        self.assertEqual(self.outside.read_text(), 'outside')

    def test_rejects_duplicate_names_link_parents_and_copy_limits_before_writing(self):
        tests = [
            ([('first', tarfile.REGTYPE, b'x'), ('first', tarfile.REGTYPE, b'y')], 8, 8),
            ([('link', tarfile.SYMTYPE, '..'), ('link/sentinel', tarfile.REGTYPE, b'x')], 8, 8),
            ([('first', tarfile.REGTYPE, b'123')], 2, 8),
            ([('first', tarfile.REGTYPE, b'x'), ('second', tarfile.REGTYPE, b'y')], 8, 1),
        ]
        for entries, maximum, count in tests:
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                self.apply(entries, maximum, count)
            self.assertEqual(list(self.base.iterdir()), [])
            self.assertEqual(self.outside.read_text(), 'outside')


if __name__ == '__main__':
    unittest.main()
