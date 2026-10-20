import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
import uuid
from types import SimpleNamespace
from unittest.mock import Mock, patch

from artifacts import MAX_OUTPUT_BYTES
from command_channel import CommandChannel, CommandEnded
from worker import SystemdBackend


class CommandChannelTests(unittest.TestCase):
    def test_unit_cleanup_releases_a_forwarder_blocked_on_unconsumed_output(self):
        process = subprocess.Popen(
            [sys.executable, '-c', "import os; os.write(2,b'ready'); os.write(1,b'x'*16000000)"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        try:
            self.assertEqual(b'ready', process.stderr.read(5))
            channel = CommandChannel(process)
            session = SimpleNamespace(unit='already-contained.service', channel=channel,
                                      unit_files=(), capture=None, incoming=None)
            backend = SystemdBackend.__new__(SystemdBackend)
            backend.assert_empty = Mock()
            with patch('worker.subprocess.run'):
                backend.stop_unit(session)
            self.assertIsNotNone(process.poll())
            self.assertIsNone(session.channel)
            self.assertEqual('', session.unit)
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=3)
            for stream in (process.stdin, process.stdout, process.stderr):
                stream.close()

    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.start_channel()
        self.addCleanup(self.close)

    def start_channel(self, greeting_delay=0):
        self.process = subprocess.Popen(
            [sys.executable, '-I', '-c',
             'import os,sys,time; time.sleep(float(sys.argv[1])); os.execv(sys.argv[2],sys.argv[2:])',
             str(greeting_delay), str(Path(__file__).with_name('bin') / 'poketto-shell'), self.root.name],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True)
        self.channel = CommandChannel(self.process)

    def close(self):
        try:
            os.killpg(self.process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self.process.wait(timeout=3)
        self.channel.close()

    def execute(self, command, timeout=3000, cancelled=lambda: False, on_ready=lambda: None):
        self.timings = {}
        return self.channel.execute(str(uuid.uuid4()), command, timeout, cancelled, lambda: 'revoked', self.timings, on_ready)

    def test_greeting_callback_precedes_delivery_of_the_first_command(self):
        self.close()
        self.start_channel(greeting_delay=0.15)
        observed = []

        def ready():
            self.assertTrue(self.channel.ready)
            self.assertFalse((Path(self.root.name) / 'delivered').exists())
            observed.append(True)

        result, _, _ = self.execute('touch delivered', on_ready=ready)
        self.assertEqual(0, result['exitCode'])
        self.assertEqual([True], observed)
        self.assertTrue((Path(self.root.name) / 'delivered').exists())

    def test_delayed_greeting_is_separate_from_command_and_not_repeated_on_reuse(self):
        self.close()
        self.start_channel(greeting_delay=0.15)

        result, _, _ = self.execute('sleep 0.15')

        self.assertEqual(0, result['exitCode'])
        self.assertGreaterEqual(self.timings['readyMs'], 100)
        self.assertGreaterEqual(self.timings['commandMs'], 100)
        self.execute('true')
        self.assertEqual(0, self.timings['readyMs'])

    def test_timeout_before_greeting_reports_only_the_wait_for_ready(self):
        self.close()
        self.start_channel(greeting_delay=1)
        ready = Mock()

        with self.assertRaises(CommandEnded) as raised:
            self.execute('touch forbidden', timeout=100, on_ready=ready)

        self.assertEqual('timeout', raised.exception.reason)
        self.assertGreaterEqual(self.timings['readyMs'], 90)
        self.assertNotIn('commandMs', self.timings)
        self.assertFalse((Path(self.root.name) / 'forbidden').exists())
        ready.assert_not_called()

    def test_real_channel_reuses_shell_and_accepts_a_command_larger_than_the_pipe(self):
        self.execute('VALUE=kept; #' + 'x' * 60000)
        result, output, truncated = self.execute('printf "$VALUE"; printf error >&2')
        self.assertEqual(0, result['exitCode'])
        self.assertEqual([b'kept', b'error'], output)
        self.assertEqual([False, False], truncated)

    def test_timeout_keeps_the_emitted_prefix_for_supervisor_containment(self):
        self.execute('true')
        with self.assertRaises(CommandEnded) as raised:
            self.execute('printf before; sleep 30', timeout=500)
        self.assertEqual('timeout', raised.exception.reason)
        self.assertEqual(b'before', raised.exception.output[0])
        self.assertEqual(0, self.timings['readyMs'])
        self.assertGreaterEqual(self.timings['commandMs'], 450)

    def test_output_limit_is_combined_and_keeps_exactly_the_bounded_prefix(self):
        with self.assertRaises(CommandEnded) as raised:
            self.execute("python3 -c 'import os; os.write(1,b\"a\"*2000000); os.write(2,b\"b\"*3000000)'")
        self.assertEqual('output_limit', raised.exception.reason)
        self.assertEqual(MAX_OUTPUT_BYTES, sum(len(stream) for stream in raised.exception.output))
        self.assertEqual([False, True], raised.exception.truncated)

    def test_revocation_stops_delivery_before_running_the_command(self):
        with self.assertRaises(CommandEnded) as raised:
            self.execute('touch forbidden', cancelled=lambda: True)
        self.assertEqual('revoked', raised.exception.reason)
        self.assertFalse((Path(self.root.name) / 'forbidden').exists())


if __name__ == '__main__':
    unittest.main()
