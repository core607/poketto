import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
import uuid

from artifacts import MAX_OUTPUT_BYTES
from command_channel import CommandChannel, CommandEnded


class CommandChannelTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.process = subprocess.Popen(
            [sys.executable, '-I', str(Path(__file__).with_name('shell_loop.py')), self.root.name],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True)
        self.channel = CommandChannel(self.process)
        self.addCleanup(self.close)

    def close(self):
        try:
            os.killpg(self.process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self.process.wait(timeout=3)
        self.channel.close()

    def execute(self, command, timeout=3000, cancelled=lambda: False):
        return self.channel.execute(str(uuid.uuid4()), command, timeout, cancelled, lambda: 'revoked')

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
