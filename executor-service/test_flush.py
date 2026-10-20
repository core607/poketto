from concurrent.futures import ThreadPoolExecutor
import ctypes
import errno
import os
from pathlib import Path
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

import test_worker as fixture
from worker import DiskSystemdBackend, SystemdBackend


class FilesystemFlushTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.backend = DiskSystemdBackend.__new__(DiskSystemdBackend)
        self.backend.mount_path = Mock(return_value=self.root)
        self.session = Mock()

    def test_flush_uses_the_copy_filesystem_without_starting_a_process(self):
        (self.root / 'acknowledged.txt').write_text('kept')
        with patch('worker.subprocess.run', side_effect=AssertionError('Unexpected process')):
            self.backend.flush(self.session)
        self.assertEqual('kept', (self.root / 'acknowledged.txt').read_text())

    def test_failed_sync_preserves_errno_and_releases_the_descriptor(self):
        descriptors = []

        def fail(fd):
            descriptors.append(fd)
            self.assertEqual(self.root.stat().st_ino, os.fstat(fd).st_ino)
            self.assertFalse(os.get_inheritable(fd))
            ctypes.set_errno(errno.EIO)
            return -1

        with patch('worker._syncfs', side_effect=fail):
            with self.assertRaises(OSError) as failed:
                self.backend.flush(self.session)
        self.assertEqual(errno.EIO, failed.exception.errno)
        self.assertEqual(1, len(descriptors))
        with self.assertRaises(OSError) as closed:
            os.fstat(descriptors[0])
        self.assertEqual(errno.EBADF, closed.exception.errno)

    def test_a_replaced_mount_path_cannot_redirect_the_flush(self):
        link = self.root / 'redirect'
        link.symlink_to(self.root, target_is_directory=True)
        self.backend.mount_path.return_value = link
        with patch('worker._syncfs') as sync:
            with self.assertRaises(OSError):
                self.backend.flush(self.session)
        sync.assert_not_called()

    def test_command_acknowledgement_waits_for_flush_and_failure_closes_the_lease(self):
        for error in (0, errno.EIO):
            with self.subTest(error=error):
                harness = fixture.ProtocolTests()
                harness.setUp()
                harness.opened()
                harness.service.backend = self.backend
                self.backend.close = Mock()
                entered, release = threading.Event(), threading.Event()

                def sync(fd):
                    entered.set()
                    if not release.wait(3):
                        raise RuntimeError('Test flush was not released')
                    ctypes.set_errno(error)
                    return -1 if error else 0

                with patch.object(SystemdBackend, 'execute', return_value={'exitCode': 0}), \
                        patch('worker._syncfs', side_effect=sync), ThreadPoolExecutor(max_workers=1) as pool:
                    running = pool.submit(harness.send, harness.execution())
                    try:
                        self.assertTrue(entered.wait(2))
                        self.assertFalse(running.done())
                    finally:
                        release.set()
                    result = running.result(2)
                session = harness.service.sessions[harness.identity['leaseId']]
                if error:
                    self.assertEqual('EXECUTOR_FAILED', result['code'])
                    self.assertTrue(session.cancelled.is_set())
                    self.backend.close.assert_called_once_with(session)
                else:
                    self.assertTrue(result['ok'], result)
                    self.assertIn('flushMs', result['result']['timings'])
                    self.backend.close.assert_not_called()


if __name__ == '__main__':
    unittest.main()
