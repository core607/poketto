"""The Rust launcher's own tests (rust/sandcrab-launch, rust/poketto-launch) cover policy
translation, tool pins, versions and ownership on real root-owned files, and the game
supervisor's test (rust/poketto-games) pins its job policy. These cases run the installed binary
as an unprivileged account and keep the Python worker's policies and launch records matched to it."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch

import worker
from worker import Session, SystemdBackend

SOURCE = Path(__file__).resolve().parent
LAUNCHER = SOURCE / 'poketto-launch'
POLICIES = SOURCE / 'rust/poketto-launch/testdata/policies.json'
SESSION = '00000000-0000-4000-8000-000000000001'


class SandboxLauncherTests(unittest.TestCase):
    def policy(self, mode):
        backend = object.__new__(SystemdBackend)
        backend.c = {'toolsRoot': '/opt/poketto/tools', 'launcher': str(LAUNCHER)}
        backend.sessions = Path('/run/poketto/sessions')
        policy = backend.sandbox_policy(Session(SESSION, (), '', 1), {'mode': mode})
        programs = str(worker.program_directory(LAUNCHER))
        return json.loads(json.dumps(policy).replace(json.dumps(programs), json.dumps('/opt/poketto/bin')))

    def launch(self, record, *extra):
        return subprocess.run([str(LAUNCHER), *extra, str(record)], capture_output=True, text=True,
                              timeout=10, env={'PATH': '/usr/bin:/bin'})

    def test_every_worker_mode_writes_the_policy_the_launcher_tests_translate(self):
        expected = json.loads(POLICIES.read_text())
        for mode in ('initialize', 'shell', 'baseline'):
            with self.subTest(mode=mode):
                self.assertEqual(expected[mode], self.policy(mode))

    def test_tools_other_accounts_could_replace_are_refused_by_every_entry(self):
        self.assertNotEqual(0, os.geteuid())
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory) / 'tools'
            for relative in ('extracted/usr/bin/bwrap',
                             'node_modules/@anthropic-ai/sandbox-runtime/vendor/seccomp/x64/apply-seccomp'):
                (tools / relative).parent.mkdir(parents=True, exist_ok=True)
                (tools / relative).write_text('#!/bin/sh\necho bubblewrap 0.6.1\n')
                (tools / relative).chmod(0o755)
            result = subprocess.run([str(LAUNCHER), '--verify', str(tools)], capture_output=True,
                                    text=True, timeout=10)
            self.assertNotEqual(0, result.returncode)
            self.assertIn('root-owned and not writable', result.stderr)
            with self.assertRaisesRegex(ValueError, 'root-owned'):
                worker.verify_launch(LAUNCHER, tools)

    def test_launcher_failure_never_falls_back_to_an_unisolated_command(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            canary = root / 'ran'
            (root / 'bootstrap').mkdir()
            settings = root / 'policy.json'
            settings.write_text(json.dumps(self.policy('shell')))
            for name, record in (
                    ('untrusted tools', {'mode': 'shell', 'root': str(root), 'tools': str(root),
                                         'settings': str(settings)}),
                    ('missing tools', {'mode': 'shell', 'root': str(root), 'tools': '/nonexistent',
                                       'settings': str(settings)}),
                    ('incomplete record', {'root': str(root)}),
                    ('duplicate keys', None)):
                with self.subTest(name=name):
                    path = root / 'record.json'
                    path.write_text(json.dumps(record) if record else
                                    '{"mode":"shell","mode":"game","root":"/","tools":"/","settings":"/"}')
                    result = self.launch(path)
                    self.assertNotEqual(0, result.returncode)
                    self.assertTrue(result.stderr.strip(), result)
                    self.assertFalse(canary.exists())
            self.assertNotEqual(0, subprocess.run([str(LAUNCHER)], capture_output=True, timeout=10).returncode)

    def test_the_worker_refuses_bad_tools_before_reading_keys_or_opening_a_socket(self):
        config = {'toolsRoot': '/tools', 'launcher': str(LAUNCHER), 'resourceSlice': 'fixture.slice'}
        with patch.object(worker.os, 'geteuid', return_value=0),                 patch.object(worker, 'load_config', return_value=config),                 patch.object(worker, 'DiskSystemdBackend'), patch.object(worker, 'ResourcePool'),                 patch.object(worker, 'verify_launch', side_effect=ValueError('bad tool')) as verify,                 patch.object(worker, 'load_pem_public_key') as key, patch.object(worker, 'Server') as server,                 patch('sys.argv', ['worker', '--config', 'fixture']), self.assertRaisesRegex(ValueError, 'bad tool'):
            try:
                worker.main()
            finally:
                key.assert_not_called()
                server.assert_not_called()
                verify.assert_called_once_with(str(LAUNCHER), '/tools')

    def test_units_run_the_launcher_binary_and_restart_cleanup_accepts_its_records(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = object.__new__(SystemdBackend)
            backend.c = json.loads(Path(__file__).with_name('config.example.json').read_text())
            backend.c['launcher'] = str(LAUNCHER)
            backend.records, backend.sessions = Path(directory) / 'records', Path(directory) / 'sessions'
            backend.records.mkdir()
            backend.sessions.mkdir()
            backend.pool = Mock()
            backend.pool.name = 'fixture.slice'
            backend.user = Mock(pw_gid=0)
            session = Session(SESSION, (), '', 1)
            with patch.object(worker.os, 'chown'), patch.object(worker, 'checked', return_value=Mock(stdout=b'')):
                args, record, settings = backend.unit_arguments(session, {'mode': 'shell'}, None)
                self.assertEqual(['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', str(LAUNCHER), str(record)], args[-5:])
                self.assertNotIn('/usr/bin/python3', args)
                self.assertTrue(record.is_file() and settings.is_file())
                backend.cleanup()
            self.assertEqual([], list(backend.records.iterdir()))


if __name__ == '__main__':
    unittest.main()
