from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import subprocess
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
        self.backend.c = {'launcher': str(Path(__file__).with_name('launcher.py'))}
        self.backend.mount_path = Mock(return_value=self.root)
        self.session = Mock()
        self.program = Path(__file__).with_name('bin') / 'poketto-syncfs'

    def test_flush_uses_the_selected_program_and_preserves_the_timeout(self):
        (self.root / 'acknowledged.txt').write_text('kept')
        with patch('worker.subprocess.run', wraps=subprocess.run) as execute:
            self.backend.flush(self.session)
        execute.assert_called_once_with([str(self.program), str(self.root)], check=True,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15)
        self.assertEqual('kept', (self.root / 'acknowledged.txt').read_text())

    def test_helper_rejects_missing_extra_and_non_directory_arguments(self):
        file = self.root / 'file'
        file.write_text('kept')
        for arguments in ([], [str(self.root), 'extra'], [str(file)], [str(self.root / 'missing')]):
            with self.subTest(arguments=arguments):
                result = subprocess.run([str(self.program), *arguments], capture_output=True, timeout=3)
                self.assertNotEqual(0, result.returncode)

    def test_a_replaced_mount_path_cannot_redirect_the_flush(self):
        link = self.root / 'redirect'
        link.symlink_to(self.root, target_is_directory=True)
        self.backend.mount_path.return_value = link
        with self.assertRaises(subprocess.CalledProcessError):
            self.backend.flush(self.session)

    def test_untrusted_program_prevents_a_flush_process(self):
        with patch('worker.trusted_executable', side_effect=ValueError('Untrusted program')), \
                patch('worker.checked') as execute:
            with self.assertRaises(ValueError):
                self.backend.flush(self.session)
        execute.assert_not_called()

    def test_command_acknowledgement_waits_for_flush_and_failure_closes_the_lease(self):
        for error in (None, subprocess.CalledProcessError(1, 'syncfs'), subprocess.TimeoutExpired('syncfs', 15)):
            with self.subTest(error=error):
                harness = fixture.ProtocolTests()
                harness.setUp()
                harness.opened()
                harness.service.backend = self.backend
                self.backend.close = Mock()
                entered, release = threading.Event(), threading.Event()

                def sync(command):
                    entered.set()
                    if not release.wait(3):
                        raise RuntimeError('Test flush was not released')
                    if error:
                        raise error

                with patch.object(SystemdBackend, 'execute', return_value={'exitCode': 0}), \
                        patch('worker.checked', side_effect=sync), ThreadPoolExecutor(max_workers=1) as pool:
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
