import base64
import errno
import hashlib
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import uuid

from artifacts import ArtifactRejected, ArtifactStore, MAX_PREVIEW_BYTES, retain_output


class ArtifactTests(unittest.TestCase):
    def test_long_output_has_a_small_preview_and_complete_or_explicitly_truncated_artifact(self):
        root = self.root / 'output-lease'
        root.mkdir()
        store = ArtifactStore(root, maximum=65536)
        self.addCleanup(store.close)
        content = b'x' * (MAX_PREVIEW_BYTES + 200)
        result = retain_output(store, [content, b'warning'], [False, False])
        self.assertEqual(MAX_PREVIEW_BYTES, len(result['stdout']))
        self.assertTrue(result['stdoutTruncated'])
        self.assertFalse(result['artifacts']['stdout']['truncated'])
        self.assertEqual(content, self.read(result['artifacts']['stdout'], store))
        cut = retain_output(store, [content, b''], [True, False])
        self.assertTrue(cut['artifacts']['stdout']['truncated'])
        self.assertFalse(cut['artifactErrors'])
        unavailable = retain_output(None, [content, b''], [False, False])
        self.assertEqual({'stdout': 'ARTIFACT_UNAVAILABLE'}, unavailable['artifactErrors'])
        self.assertFalse(unavailable['artifacts'])
        full = retain_output(self.store, [content, b''], [False, False])
        self.assertEqual({'stdout': 'ARTIFACT_CAPACITY'}, full['artifactErrors'])

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.work = self.root / 'work/repository'
        self.work.mkdir(parents=True)
        self.now = 10
        self.store = ArtifactStore(self.root, clock=lambda: self.now, maximum=32, count=3)
        self.addCleanup(self.store.close)

    def read(self, metadata, store=None):
        result = (store or self.store).read(metadata['artifactId'], 0, 65536)
        return base64.b64decode(result['data'])

    def test_capture_retains_original_bytes_and_scopes_identical_objects_to_each_store(self):
        source = self.work / 'note.txt'
        source.write_bytes(b'original')
        item = self.store.capture('note.txt', 'text/plain')
        source.write_bytes(b'local edit')
        self.assertEqual(b'original', self.read(item))
        self.assertEqual(hashlib.sha256(b'original').hexdigest(), item['sha256'])
        other_root = self.root / 'other-lease'
        other_root.mkdir()
        other = ArtifactStore(other_root)
        self.addCleanup(other.close)
        equal = other.put('stdout.txt', b'original')
        self.assertNotEqual(item['artifactId'], equal['artifactId'])
        self.assertNotEqual((self.store.directory / item['artifactId']).stat().st_ino,
                            (other.directory / equal['artifactId']).stat().st_ino)
        with self.assertRaises(ArtifactRejected):
            self.read(item, other)
        self.assertEqual(b'original', self.read(equal, other))
        self.assertFalse(list(self.root.glob('outgoing-*')))

    def test_expiry_releases_capacity_and_retired_handles_cannot_read_new_objects(self):
        old = self.store.put('stdout.txt', b'a' * 32, truncated=True)
        self.assertTrue(old['truncated'])
        with self.assertRaises(ArtifactRejected) as rejected:
            self.store.put('stderr.txt', b'x')
        self.assertEqual('ARTIFACT_CAPACITY', rejected.exception.code)
        self.now += 300
        with self.assertRaises(ArtifactRejected):
            self.read(old)
        self.assertFalse(list(self.store.directory.iterdir()))
        new = self.store.put('stdout.txt', b'b' * 32)
        self.assertNotEqual(old['artifactId'], new['artifactId'])
        self.assertEqual(b'b' * 32, self.read(new))
        self.store.remove(new['artifactId'])
        self.store.remove(new['artifactId'])
        with self.assertRaises(ArtifactRejected):
            self.read(new)

    def test_unsafe_sources_and_invalid_ranges_never_expose_bytes(self):
        outside = self.root / 'outside'
        outside.write_bytes(b'secret')
        (self.work / 'symbolic').symlink_to(outside)
        os.link(outside, self.work / 'hard')
        os.mkfifo(self.work / 'fifo')
        for name in ('../outside', '/etc/passwd', 'symbolic', 'hard', 'fifo', 'missing'):
            with self.subTest(name=name), self.assertRaises(ArtifactRejected):
                self.store.capture(name, 'application/octet-stream')
        safe = self.store.put('stdout.txt', b'hello')
        for offset, limit in ((-1, 1), (6, 1), (True, 1), (0, 65537), (0, 0)):
            with self.subTest(offset=offset, limit=limit), self.assertRaises(ArtifactRejected):
                self.store.read(safe['artifactId'], offset, limit)
        middle = self.store.read(safe['artifactId'], 1, 2)
        self.assertEqual(b'el', base64.b64decode(middle['data']))
        self.assertEqual(b'', base64.b64decode(self.store.read(safe['artifactId'], 5, 1)['data']))
        self.assertEqual(b'secret', outside.read_bytes())

    def test_content_tampering_is_detected_and_close_removes_all_retained_files(self):
        item = self.store.put('stdout.txt', b'original')
        path = self.store.directory / item['artifactId']
        path.write_bytes(b'changed!')
        with self.assertRaises(ArtifactRejected):
            self.read(item)
        self.store.put('stderr.txt', b'other')
        self.store.close()
        self.assertFalse(list(self.store.directory.iterdir()))
        with self.assertRaises(ArtifactRejected):
            self.store.put('stdout.txt', b'closed')

    def test_count_bytes_and_declared_type_bounds_are_independent(self):
        for _ in range(3):
            self.store.put('stdout.txt', b'')
        with self.assertRaises(ArtifactRejected):
            self.store.put('stdout.txt', b'')
        self.now += 300
        (self.work / 'too-large').write_bytes(b'x' * 33)
        with self.assertRaises(ArtifactRejected):
            self.store.capture('too-large', 'text/plain')
        for media_type in ('text/plain\nHeader: x', 'text', 'text/' + 'a' * 128):
            with self.subTest(media_type=media_type), self.assertRaises(ArtifactRejected):
                self.store.put('stdout.txt', b'x', media_type)
        self.assertFalse(list(self.root.glob('outgoing-*')))
        self.assertFalse(list(self.store.directory.iterdir()))

    def test_partial_write_and_identifier_collision_preserve_existing_artifacts(self):
        original = self.store.put('stdout.txt', b'keep')
        with patch('artifacts.os.write', side_effect=OSError(errno.ENOSPC, 'synthetic full storage')):
            with self.assertRaises(ArtifactRejected):
                self.store.put('stderr.txt', b'new')
        with patch('artifacts.uuid.uuid4', return_value=uuid.UUID(original['artifactId'])):
            with self.assertRaises(ArtifactRejected):
                self.store.put('stdout.txt', b'overwrite')
            (self.work / 'source').write_bytes(b'overwrite')
            with self.assertRaises(ArtifactRejected):
                self.store.capture('source', 'text/plain')
        self.assertEqual(b'keep', self.read(original))
        self.assertEqual(1, len(list(self.store.directory.iterdir())))
        self.assertFalse(list(self.root.glob('outgoing-*')))
