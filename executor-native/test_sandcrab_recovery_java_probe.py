"""Offline controller result tests; no daemon, sandbox or systemd command is started."""
import json
from pathlib import Path
import tempfile
import unittest

from sandcrab_recovery_java_probe import read_result


class RecoveryResultTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.consumer = self.root / 'consumer'
        self.consumer.mkdir()
        self.report = self.root / 'attempt.json'

    def test_warning_output_is_diagnostic_and_cannot_change_the_structured_result(self):
        self.report.with_suffix('.stdout').write_text(
            '16:03:04.443 WARN SandboxHttp -- Sandbox exchange failed during response-body\n'
            'java.io.IOException: incomplete worker response\n{"result":"FAIL"}\n')
        raw = b'{"result":"PASS","checks":["observed-interruption"],"recoveries":[]}'
        (self.consumer / 'result.json').write_bytes(raw)
        self.assertEqual(json.loads(raw), read_result(self.consumer, self.report))
        self.assertEqual(raw, self.report.with_suffix('.java.json').read_bytes())

    def test_stdout_cannot_substitute_for_a_missing_result(self):
        self.report.with_suffix('.stdout').write_text('{"result":"PASS"}\n')
        with self.assertRaises(AssertionError):
            read_result(self.consumer, self.report)
        self.assertFalse(self.report.with_suffix('.java.json').exists())

    def test_malformed_result_is_retained_before_parsing_fails(self):
        raw = b'{"result":"PASS"}\n{"result":"FAIL"}'
        (self.consumer / 'result.json').write_bytes(raw)
        with self.assertRaises(json.JSONDecodeError):
            read_result(self.consumer, self.report)
        self.assertEqual(raw, self.report.with_suffix('.java.json').read_bytes())

    def test_oversized_result_is_refused_before_copying(self):
        (self.consumer / 'result.json').write_bytes(b'x' * (1024 * 1024 + 1))
        with self.assertRaises(AssertionError):
            read_result(self.consumer, self.report)
        self.assertFalse(self.report.with_suffix('.java.json').exists())

    def test_previous_result_evidence_is_never_overwritten(self):
        original = b'{"result":"FAIL"}'
        self.report.with_suffix('.java.json').write_bytes(original)
        (self.consumer / 'result.json').write_text('{"result":"PASS"}')
        with self.assertRaises(FileExistsError):
            read_result(self.consumer, self.report)
        self.assertEqual(original, self.report.with_suffix('.java.json').read_bytes())


if __name__ == '__main__':
    unittest.main()
