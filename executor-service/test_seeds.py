import os
from pathlib import Path
import tempfile
import unittest
import uuid

from disk_pool import DiskPool


def pool(root):
    """A pool without its XFS checks: seed handling needs only the directory layout."""
    disks = object.__new__(DiskPool)
    disks.root = Path(root)
    disks.seeds = disks.root / 'seeds'
    disks.seeds.mkdir(mode=0o700)
    return disks


class SeedTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.disks = pool(self.root)
        self.workspace = str(uuid.uuid4())
        self.copy = self.copy_tree('a' * 40)

    def copy_tree(self, commit):
        copy = self.root / ('copy-' + commit[:4])
        (copy / 'work' / 'repository').mkdir(parents=True)
        (copy / 'snapshot.bundle').write_bytes(b'bundle ' + commit.encode())
        (copy / 'work' / 'repository' / 'README.md').write_text('original\n')
        (copy / '.copy.json').write_text('{"identity": "never seeded"}')
        (copy / 'tmp').mkdir()
        (copy / 'tmp' / 'scratch').write_text('runtime state')
        return copy

    def test_a_seed_holds_only_the_export_and_repository_and_stays_independent(self):
        self.disks.capture_seed(self.copy, self.workspace, 'full', 'a' * 40)
        seed = self.disks.seed(self.workspace, 'full', 'a' * 40)
        self.assertEqual({'snapshot.bundle', 'work'}, {entry.name for entry in seed.iterdir()})

        target = self.root / 'new-copy'
        target.mkdir()
        self.assertTrue(self.disks.populate_from_seed(target, self.workspace, 'full', 'a' * 40))
        (target / 'work' / 'repository' / 'README.md').write_text('edited by a command\n')
        self.assertEqual('original\n', (seed / 'work' / 'repository' / 'README.md').read_text())
        self.assertEqual('original\n', (self.copy / 'work' / 'repository' / 'README.md').read_text())

    def test_an_existing_seed_is_kept_and_other_keys_find_nothing(self):
        self.disks.capture_seed(self.copy, self.workspace, 'full', 'a' * 40)
        (self.copy / 'work' / 'repository' / 'README.md').write_text('changed later\n')
        self.disks.capture_seed(self.copy, self.workspace, 'full', 'a' * 40)
        seed = self.disks.seed(self.workspace, 'full', 'a' * 40)
        self.assertEqual('original\n', (seed / 'work' / 'repository' / 'README.md').read_text())
        self.assertIsNone(self.disks.seed(self.workspace, 'public', 'a' * 40))
        self.assertIsNone(self.disks.seed(str(uuid.uuid4()), 'full', 'a' * 40))
        self.assertFalse(self.disks.populate_from_seed(self.root, self.workspace, 'full', 'b' * 40))
        with self.assertRaises(ValueError):
            self.disks.seed('../escape', 'full', 'a' * 40)

    def test_only_two_seeds_per_scope_survive_unless_recently_used(self):
        for commit in ('a' * 40, 'b' * 40, 'c' * 40):
            self.disks.capture_seed(self.copy_tree(commit) if commit != 'a' * 40 else self.copy,
                                    self.workspace, 'full', commit)
            old = 10 ** 9
            os.utime(self.disks.seed(self.workspace, 'full', commit), ns=(old, old))
        self.disks.capture_seed(self.copy_tree('d' * 40), self.workspace, 'full', 'd' * 40)
        remaining = sorted(entry.name[0] for entry in (self.disks.seeds / self.workspace / 'full').iterdir())
        self.assertEqual(2, len(remaining))
        self.assertIn('d', remaining)

    def test_interrupted_captures_are_collected(self):
        stage = self.disks.seeds / self.workspace / 'full' / ('.creating-' + str(uuid.uuid4()))
        stage.mkdir(parents=True)
        self.disks._collect_seed_staging()
        self.assertFalse(stage.exists())


if __name__ == '__main__':
    unittest.main()
