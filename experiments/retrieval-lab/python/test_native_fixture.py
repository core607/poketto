"""Offline input and staging checks; these do not establish native containment."""
import json
import os
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import native_fixture


class CorpusInputTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name).resolve()
        self.lab, self.data = self.root / 'lab', self.root / 'data'
        self.lab.mkdir()
        self.data.mkdir()
        self.addCleanup(patch.stopall)
        patch.object(native_fixture, 'LAB', self.lab).start()
        (self.data / 'fiqa.db').write_bytes(b'fixture database, not the actual corpus')
        (self.data / 'corpus.bundle').write_bytes(b'fixture bundle, not a real Git bundle')
        self.pins = {'databaseSha256': native_fixture.sha(self.data / 'fiqa.db'), 'dimensions': 4096}
        self.audit = {'pins': self.pins, 'databaseSha256': self.pins['databaseSha256'], 'integrity': 'ok',
                      'dimensions': 4096, 'documents': 57600, 'standardBeir': {'extraIds': [], 'changedTextIds': []}}
        self.manifest = {'documents': 57600, 'commit': 'a' * 40,
                         'documentPaths': {str(i): f'corpus/topic/page/{i}.md' for i in range(57600)},
                         'bundleBytes': (self.data / 'corpus.bundle').stat().st_size,
                         'bundleSha256': native_fixture.sha(self.data / 'corpus.bundle')}
        self.write()

    def write(self):
        (self.lab / 'dataset.json').write_text(json.dumps(self.pins))
        (self.data / 'audit.json').write_text(json.dumps(self.audit))
        (self.data / 'corpus-manifest.json').write_text(json.dumps(self.manifest))

    def test_complete_manifest_and_both_byte_hashes_are_required(self):
        self.assertEqual(native_fixture.corpus(self.data), self.manifest)
        (self.data / 'corpus.bundle').write_bytes(b'changed bundle')
        with self.assertRaises(AssertionError):
            native_fixture.corpus(self.data)
        (self.data / 'fiqa.db').write_bytes(b'changed database')
        with self.assertRaisesRegex(AssertionError, 'database changed'):
            native_fixture.corpus(self.data)

    def test_smaller_corpus_or_missing_paths_cannot_pass_as_complete(self):
        self.manifest['documents'] = self.audit['documents'] = 10
        self.write()
        with self.assertRaisesRegex(AssertionError, 'complete audited corpus'):
            native_fixture.corpus(self.data)
        self.manifest['documents'] = self.audit['documents'] = 57600
        self.manifest['documentPaths'].pop('0')
        self.write()
        with self.assertRaises(AssertionError):
            native_fixture.corpus(self.data)

    def test_changed_originals_and_path_escape_are_rejected(self):
        self.audit['standardBeir']['changedTextIds'] = ['0']
        self.write()
        with self.assertRaises(AssertionError):
            native_fixture.corpus(self.data)
        self.audit['standardBeir']['changedTextIds'] = []
        for path in ('/corpus/0.md', 'corpus/../../0.md', 'corpus\\0.md', 'corpus/README.md'):
            with self.subTest(path=path):
                self.manifest['documentPaths']['0'] = path
                self.write()
                with self.assertRaises(AssertionError):
                    native_fixture.corpus(self.data)

    @unittest.skipUnless(hasattr(os, 'chown'), 'Linux fixture ownership')
    def test_private_umask_still_allows_client_to_read_only_staged_consumer_files(self):
        import stat
        for name in ('src/native-acceptance.js', 'test/worker-process-fixture.js'):
            path = self.lab / 'dist' / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('fixture code')
        (self.lab / '.env').write_text('UNRELATED_PRIVATE_STATE=never-stage')
        probe = SimpleNamespace(root=self.root / 'owned', receipt={},
                                client=SimpleNamespace(pw_uid=os.getuid(), pw_gid=os.getgid()))
        probe.root.mkdir()
        previous = os.umask(0o077)
        try:
            consumer, data = native_fixture.stage(probe, self.data)
        finally:
            os.umask(previous)
        self.assertEqual(stat.S_IMODE(consumer.stat().st_mode), 0o755)
        for path in consumer.rglob('*'):
            self.assertEqual(stat.S_IMODE(path.stat().st_mode), 0o755 if path.is_dir() else 0o644)
        self.assertEqual(stat.S_IMODE(data.stat().st_mode), 0o700)
        self.assertEqual({p.name for p in data.iterdir()}, {'corpus.bundle', 'corpus-manifest.json'})
        self.assertFalse((consumer / '.env').exists())
        self.assertEqual(len(probe.receipt['consumerFiles']), 2)


if __name__ == '__main__':
    unittest.main()
