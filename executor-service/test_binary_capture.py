import base64
import hashlib
import os
from pathlib import Path
import tempfile
import unittest

from binary_capture import BinaryCapture, MAX_BINARY_BYTES
from session_files import CaptureRejected


class BinaryCaptureTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.repository = self.root / 'work/repository'
        self.repository.mkdir(parents=True)

    def test_binary_snapshot_survives_source_changes_and_release_removes_staging(self):
        data = bytes(range(256)) * 1000
        path = self.repository / 'image.bin'
        path.write_bytes(data)
        capture = BinaryCapture(self.root, 'image.bin')
        self.addCleanup(capture.close)
        manifest = capture.manifest()
        self.assertEqual(hashlib.sha256(data).hexdigest(), manifest['writes'][0]['sha256'])
        path.write_bytes(b'changed after capture')
        actual = bytearray()
        while len(actual) < len(data):
            actual.extend(base64.b64decode(capture.chunk(0, len(actual), 65536)['data']))
        self.assertEqual(data, actual)
        self.assertEqual(0o600, capture.stage.stat().st_mode & 0o777)
        capture.close()
        self.assertFalse(capture.stage.exists())
        with self.assertRaises(CaptureRejected):
            capture.chunk(0, 0, 1)

    def test_unsafe_sources_and_sparse_oversize_files_leave_no_snapshot(self):
        outside = self.root / 'outside'
        outside.write_bytes(b'private')
        (self.repository / 'symbolic').symlink_to(outside)
        os.link(outside, self.repository / 'hard')
        os.mkfifo(self.repository / 'fifo')
        with (self.repository / 'large').open('wb') as stream:
            stream.truncate(MAX_BINARY_BYTES + 1)
        for path in ['symbolic', 'hard', 'fifo', 'large', '.git/config', '../outside']:
            with self.subTest(path=path), self.assertRaises(CaptureRejected):
                BinaryCapture(self.root, path)
        self.assertFalse(list(self.root.glob('outgoing-*')))
        self.assertEqual(b'private', outside.read_bytes())

    def test_cancellation_during_copy_removes_partial_snapshot(self):
        (self.repository / 'data').write_bytes(b'x' * 100000)
        with self.assertRaises(CaptureRejected):
            BinaryCapture(self.root, 'data', lambda: True)
        self.assertFalse(list(self.root.glob('outgoing-*')))
