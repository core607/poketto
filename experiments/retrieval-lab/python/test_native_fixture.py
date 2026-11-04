"""Offline input and staging checks; these do not establish native containment."""
import json
from pathlib import Path
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

    def test_only_the_two_public_corpus_inputs_are_staged(self):
        (self.data / '.env').write_text('unused')
        target = self.root / 'client-data'
        native_fixture.stage(self.data, target)
        self.assertEqual({item.name for item in target.iterdir()}, {'corpus.bundle', 'corpus-manifest.json'})
        self.assertEqual((target / 'corpus.bundle').read_bytes(), (self.data / 'corpus.bundle').read_bytes())


if __name__ == '__main__':
    unittest.main()
