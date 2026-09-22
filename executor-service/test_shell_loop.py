import base64
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile
import time
import unittest
import uuid

from shell_loop import MAX_RETAINED_STREAMS


class ShellLoopTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.TemporaryDirectory()
        self.addCleanup(self.root.cleanup)
        self.process = subprocess.Popen(
            [sys.executable, '-I', str(Path(__file__).with_name('shell_loop.py')), self.root.name],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True)
        self.addCleanup(self.close)
        self.selector = selectors.DefaultSelector()
        self.selector.register(self.process.stdout, selectors.EVENT_READ)
        self.pending = bytearray()
        self.assertEqual({'kind': 'ready'}, self.frame())

    def close(self):
        # The production supervisor owns descendant cleanup; this harness owns its process group.
        try:
            os.killpg(self.process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        self.process.wait(timeout=3)
        self.selector.close()
        for pipe in (self.process.stdin, self.process.stdout, self.process.stderr):
            pipe.close()

    def frame(self):
        deadline = time.monotonic() + 5
        while b'\n' not in self.pending:
            remaining = deadline - time.monotonic()
            self.assertGreater(remaining, 0, 'Shell channel did not complete')
            self.assertTrue(self.selector.select(remaining), 'Shell channel timed out')
            chunk = os.read(self.process.stdout.fileno(), 16384)
            if not chunk:
                self.fail('Shell channel closed: ' + self.process.stderr.read().decode())
            self.pending.extend(chunk)
        raw, _, rest = self.pending.partition(b'\n')
        self.pending = bytearray(rest)
        return json.loads(raw)

    def execute(self, command):
        identity = str(uuid.uuid4())
        self.process.stdin.write(json.dumps({'executionId': identity, 'command': command}).encode() + b'\n')
        self.process.stdin.flush()
        output = {'stdout': bytearray(), 'stderr': bytearray()}
        while True:
            frame = self.frame()
            self.assertEqual(identity, frame['executionId'])
            if frame['kind'] == 'complete':
                return frame, {stream: bytes(value) for stream, value in output.items()}
            self.assertEqual('output', frame['kind'])
            output[frame['stream']].extend(base64.b64decode(frame['data'], validate=True))

    def test_working_directory_environment_functions_aliases_and_background_survive(self):
        result, output = self.execute(
            "mkdir child; cd child; export VALUE='猫 words'; "
            "greeting() { printf '%s' \"$VALUE\"; }; alias hello=greeting; "
            "printf temporary > ../temp-state; sleep 30 & background=$!; printf ready")
        self.assertEqual(0, result['exitCode'])
        self.assertEqual(b'ready', output['stdout'])
        result, output = self.execute("test \"$PWD\" = \"$(dirname \"$PWD\")/child\" && "
                                      "kill -0 \"$background\" && hello; cat ../temp-state")
        self.assertEqual(0, result['exitCode'])
        self.assertFalse(result['shellExited'])
        self.assertEqual('猫 wordstemporary'.encode(), output['stdout'])

    def test_old_background_output_never_enters_the_next_command(self):
        result, output = self.execute(
            "(while test ! -e release; do sleep 0.01; done; "
            "printf old-out; printf old-err >&2; touch finished) & printf first")
        self.assertEqual(0, result['exitCode'])
        self.assertEqual(b'first', output['stdout'])
        result, output = self.execute(
            "touch release; while test ! -e finished; do sleep 0.01; done; printf second; printf error >&2")
        self.assertEqual(0, result['exitCode'])
        self.assertEqual({'stdout': b'second', 'stderr': b'error'}, output)

    def test_completion_keeps_all_binary_output_without_a_trailing_newline(self):
        result, output = self.execute("python3 -c 'import os; os.write(1, bytes(range(256))*400); os.write(2, b\"tail\")'")
        self.assertEqual(0, result['exitCode'])
        self.assertEqual(bytes(range(256)) * 400, output['stdout'])
        self.assertEqual(b'tail', output['stderr'])

    def test_false_literal_newlines_and_shell_exit_report_their_own_status(self):
        result, _ = self.execute('false')
        self.assertEqual(1, result['exitCode'])
        result, output = self.execute("printf '%s' 'literal\nquote\"; false'\nprintf '\\nlast'")
        self.assertEqual(0, result['exitCode'])
        self.assertEqual(b'literal\nquote"; false\nlast', output['stdout'])
        result, output = self.execute('printf goodbye; exit 7')
        self.assertEqual(7, result['exitCode'])
        self.assertTrue(result['shellExited'])
        self.assertEqual(b'goodbye', output['stdout'])

    def test_completed_command_pipes_are_released(self):
        baseline = len(list(Path('/proc', str(self.process.pid), 'fd').iterdir()))
        for _ in range(80):
            result, _ = self.execute('true')
            self.assertEqual(0, result['exitCode'])
        # One command's EOF may be pending in the selector when its completion is read.
        current = len(list(Path('/proc', str(self.process.pid), 'fd').iterdir()))
        self.assertLessEqual(current, baseline + 4)

    def test_retained_output_writers_are_bounded_without_resetting_the_shell(self):
        baseline = len(list(Path('/proc', str(self.process.pid), 'fd').iterdir()))
        self.execute('VALUE=retained')
        for _ in range(MAX_RETAINED_STREAMS // 2 + 10):
            # Retain old pipe writers in bash itself, below the test container's 64-task bound.
            result, _ = self.execute('exec {held_out}>&1; exec {held_err}>&2')
            self.assertEqual(0, result['exitCode'])
        result, output = self.execute('printf "$VALUE"')
        self.assertEqual(0, result['exitCode'])
        self.assertEqual(b'retained', output['stdout'])
        current = len(list(Path('/proc', str(self.process.pid), 'fd').iterdir()))
        self.assertLessEqual(current, baseline + MAX_RETAINED_STREAMS)

    def test_oversized_command_ends_driver_without_evaluation(self):
        self.process.stdin.write(json.dumps({
            'executionId': str(uuid.uuid4()), 'command': 'x' * 65537}).encode() + b'\n')
        self.process.stdin.flush()
        self.assertNotEqual(0, self.process.wait(timeout=3))
        self.assertIn(b'Invalid command source', self.process.stderr.read())


if __name__ == '__main__':
    unittest.main()
