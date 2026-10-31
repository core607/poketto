"""Offline observation checks; these do not substitute for a real migration run."""
import json
from pathlib import Path
import tempfile
import unittest

from migration_probe_evidence import operator_result, require_execute, require_prepare, snapshot


class MigrationEvidenceTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.trace = self.root / 'trace'

    def write_trace(self, requests):
        self.trace.write_text('\n'.join('123 write(7, ' + json.dumps(method + ' ' + path +
            ' HTTP/1.1\r\nHost: sandcrab\r\n\r\n') + ', 200) = 200' for method, path in requests))

    def test_controller_result_uses_the_exact_exit_status_stream(self):
        output, error = self.root / 'stdout', self.root / 'stderr'
        output.write_text('{"status":"prepared"}\n')
        error.write_text('')
        self.assertEqual(operator_result(output, error, 0), {'status': 'prepared'})
        output.write_text('')
        error.write_text('{"status":"deferred","sourceRetained":true}\n')
        self.assertEqual(operator_result(output, error, 1)['status'], 'deferred')
        with self.assertRaises(AssertionError):
            operator_result(output, error, 0)

    def test_warn_or_second_json_never_silently_reclassifies_an_attempt(self):
        output, error = self.root / 'stdout', self.root / 'stderr'
        error.write_text('')
        for data in ('WARN controller output\n{"status":"published"}\n',
                     '{"status":"published"}\n{"status":"published"}\n'):
            output.write_text(data)
            with self.assertRaises(json.JSONDecodeError):
                operator_result(output, error, 0)

    def test_prepare_needs_positive_info_observation_and_rejects_any_mutation(self):
        self.write_trace([])
        with self.assertRaises(AssertionError):
            require_prepare(self.trace)
        self.write_trace([('GET', '/v1/info')])
        self.assertEqual(require_prepare(self.trace), [['GET', '/v1/info']])
        self.write_trace([('GET', '/v1/info'), ('POST', '/v1/volumes')])
        with self.assertRaises(AssertionError):
            require_prepare(self.trace)

    def test_unknown_request_framing_fails_instead_of_disappearing(self):
        self.trace.write_text('123 write(7, "POST /v1/runs", 13) = 13')
        with self.assertRaisesRegex(AssertionError, 'Unrecognized'):
            require_prepare(self.trace)

    def test_real_docker_guard_http_is_not_counted_as_native_service_traffic(self):
        self.write_trace([('GET', '/v1.47/containers/json?all=1'), ('GET', '/v1/info')])
        self.assertEqual(require_prepare(self.trace), [['GET', '/v1/info']])

    def test_execution_requires_both_real_uploads_and_rejects_guest_commands(self):
        plan = {part: {'upload': {'id': part + '-upload'}, 'session': {'id': part + '-session'}}
                for part in ('work', 'home')}
        plan['volume'] = {'id': 'retained-copy'}
        calls = [('POST', '/v1/volumes')]
        for part in ('work', 'home'):
            calls += [('POST', '/v1/sessions'), ('POST', '/v1/uploads'),
                      ('PUT', '/v1/uploads/' + part + '-upload/content'),
                      ('POST', '/v1/sessions/' + part + '-session/close')]
        self.write_trace(calls)
        self.assertEqual(len(require_execute(self.trace, plan)), 9)
        for changed in (calls[:-2], calls + [('POST', '/v1/sessions/work-session/commands')],
                        calls + [('POST', '/v1/runs')], calls + [('GET', '/v1/calls')],
                        calls + [('PUT', '/v1/uploads/work-upload/content')]):
            self.write_trace(changed)
            with self.assertRaises(AssertionError):
                require_execute(self.trace, plan)

    def test_snapshot_distinguishes_root_bytes_modes_symlinks_and_hardlinks(self):
        import os
        for name in ('work', 'home'):
            (self.root / name).mkdir(mode=0o700)
            (self.root / name / 'same').write_text(name)
        os.link(self.root / 'work/same', self.root / 'work/link')
        (self.root / 'home/relative').symlink_to('same')
        initial = snapshot(self.root)
        self.assertEqual(initial['hardlinks'], [['work/link', 'work/same']])
        self.assertNotEqual(initial['files']['work/same']['sha256'], initial['files']['home/same']['sha256'])
        self.assertEqual(initial['files']['home/relative']['target'], 'same')
        (self.root / 'home/same').chmod(0o700)
        self.assertNotEqual(snapshot(self.root), initial)


if __name__ == '__main__':
    unittest.main()
