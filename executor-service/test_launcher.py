import copy
import hashlib
import json
from pathlib import Path
import stat
import tempfile
import unittest
from unittest.mock import Mock, patch

import launcher
import worker
import game_worker
from worker import Session, SystemdBackend
from game_worker import GameBackend


class SandboxLauncherTests(unittest.TestCase):
    def policy(self, mode='shell'):
        backend = object.__new__(GameBackend if mode == 'game' else SystemdBackend)
        backend.c = {'toolsRoot': '/opt/poketto/tools', 'launcher': str(Path(__file__).with_name('launcher.py'))}
        backend.sessions = Path('/run/poketto/sessions')
        session = Session('00000000-0000-4000-8000-000000000001', (), '', 1)
        return backend.sandbox_policy(session, {'mode': mode})

    def arguments(self, policy):
        return launcher.sandbox_arguments('/tools/bwrap', '/tools/apply-seccomp',
                                          policy, ['/bin/bash', '-c', 'printf "$HOME"'])

    def test_every_worker_mode_reaches_the_filter_with_a_literal_command(self):
        for mode in ('initialize', 'shell', 'baseline', 'game'):
            with self.subTest(mode=mode):
                args = self.arguments(self.policy(mode))
                self.assertEqual(['/tools/apply-seccomp', '/bin/bash', '-c', 'printf "$HOME"'], args[-4:])
                self.assertIn('--unshare-net', args)
                self.assertIn('--unshare-user', args)
                self.assertIn('--unshare-pid', args)
                self.assertIn('--die-with-parent', args)
                self.assertIn('--new-session', args)
                self.assertEqual(['--cap-drop', 'ALL'], args[args.index('--cap-drop'):args.index('--cap-drop') + 2])
                self.assertEqual(['--remount-ro', '/'], args[args.index('--remount-ro'):args.index('--remount-ro') + 2])
                self.assertNotIn('--ro-bind / /', ' '.join(args))
                for source in ('/dev', '/proc'):
                    self.assertNotIn(['--ro-bind', source, source],
                                     [args[i:i + 3] for i in range(len(args))])

    def test_policy_extensions_and_overlapping_grants_fail_closed(self):
        mutations = (
            lambda p: p['network'].update(allowAllUnixSockets=True),
            lambda p: p['network']['allowedDomains'].append('example.invalid'),
            lambda p: p.update(enableWeakerNestedSandbox=True),
            lambda p: p['filesystem']['denyWrite'].append('/tmp/secret'),
            lambda p: p['filesystem']['denyRead'].append('/usr/secret'),
            lambda p: p['filesystem']['allowWrite'].append('/usr/local'),
            lambda p: p['filesystem']['allowRead'].append('/tmp/subdir'),
            lambda p: p['filesystem']['allowRead'].append('/'),
            lambda p: p['filesystem']['allowRead'].append('/var/../root'),
        )
        for mutate in mutations:
            policy = copy.deepcopy(self.policy())
            mutate(policy)
            with self.subTest(policy=policy), self.assertRaises(ValueError):
                self.arguments(policy)

    def test_tool_pin_and_minimum_version_are_both_required(self):
        with tempfile.TemporaryDirectory() as directory:
            tools = Path(directory)
            seccomp = tools / 'node_modules/@anthropic-ai/sandbox-runtime/vendor/seccomp/x64/apply-seccomp'
            seccomp.parent.mkdir(parents=True)
            seccomp.write_bytes(b'fixture static helper')
            digest = hashlib.sha256(seccomp.read_bytes()).hexdigest()
            with patch.object(launcher, 'trusted_executable') as trusted, \
                    patch.object(launcher, 'SECCOMP_SHA256', digest), \
                    patch.object(launcher.subprocess, 'run', return_value=Mock(stdout='bubblewrap 0.6.1\n')) as run:
                self.assertEqual(seccomp, launcher.verify_tools(tools)[1])
                self.assertEqual(2, trusted.call_count)
                for version in ('bubblewrap 0.6.0', 'bubblewrap unknown', ''):
                    run.return_value.stdout = version
                    with self.subTest(version=version), self.assertRaises(ValueError):
                        launcher.verify_tools(tools)
                run.reset_mock()
                seccomp.write_bytes(b'modified')
                with self.assertRaisesRegex(ValueError, 'checksum'):
                    launcher.verify_tools(tools)
                run.assert_not_called()

    def test_tool_and_ancestor_ownership_cannot_be_bypassed_by_links_or_modes(self):
        target = Path('/prepared/tools/bwrap')
        for unsafe in (Mock(st_mode=stat.S_IFREG | 0o775, st_uid=0),
                       Mock(st_mode=stat.S_IFREG | 0o755, st_uid=1000),
                       Mock(st_mode=stat.S_IFLNK | 0o777, st_uid=0)):
            with patch.object(Path, 'lstat', return_value=unsafe), self.assertRaises(ValueError):
                launcher.trusted_executable(target)
        safe_file = Mock(st_mode=stat.S_IFREG | 0o755, st_uid=0)
        unsafe_parent = Mock(st_mode=stat.S_IFDIR | 0o777, st_uid=0)
        with patch.object(Path, 'lstat', side_effect=[safe_file, unsafe_parent]), self.assertRaises(ValueError):
            launcher.trusted_executable(target)

    def test_supervisors_refuse_bad_tools_before_reading_keys_or_opening_a_socket(self):
        for module, load, backend in ((worker, 'load_config', 'DiskSystemdBackend'),
                                      (game_worker, 'load_game_config', 'GameBackend')):
            with self.subTest(module=module.__name__), \
                    patch.object(module.os, 'geteuid', return_value=0), \
                    patch.object(module, load, return_value={'toolsRoot': '/tools', 'resourceSlice': 'fixture.slice'}), \
                    patch.object(module, backend), patch.object(module, 'ResourcePool'), \
                    patch.object(module, 'verify_tools', side_effect=ValueError('bad tool')), \
                    patch.object(module, 'load_pem_public_key') as key, patch.object(module, 'Server') as server, \
                    patch('sys.argv', ['worker', '--config', 'fixture']), self.assertRaisesRegex(ValueError, 'bad tool'):
                try:
                    module.main()
                finally:
                    key.assert_not_called()
                    server.assert_not_called()

    def test_launcher_failure_never_falls_back_to_an_unisolated_command(self):
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / 'record.json'
            record.write_text(json.dumps({'root': '/fixture', 'tools': '/tools'}))
            with patch.object(launcher.os, 'geteuid', return_value=1000), \
                    patch.object(launcher, 'verify_tools', side_effect=ValueError('bad tool')), \
                    patch.object(launcher.os, 'execve') as execute, \
                    patch('sys.argv', ['launcher', str(record)]), self.assertRaisesRegex(ValueError, 'bad tool'):
                try:
                    launcher.main()
                finally:
                    execute.assert_not_called()

    def test_restart_cleanup_accepts_the_records_the_launcher_actually_creates(self):
        with tempfile.TemporaryDirectory() as directory:
            backend = object.__new__(SystemdBackend)
            backend.c = json.loads(Path(__file__).with_name('config.example.json').read_text())
            backend.c['launcher'] = str(Path(__file__).with_name('launcher.py'))
            backend.records, backend.sessions = Path(directory) / 'records', Path(directory) / 'sessions'
            backend.records.mkdir()
            backend.sessions.mkdir()
            backend.pool = Mock()
            backend.pool.name = 'fixture.slice'
            backend.user = Mock(pw_gid=0)
            session = Session('00000000-0000-4000-8000-000000000001', (), '', 1)
            with patch.object(worker.os, 'chown'), patch.object(worker, 'checked', return_value=Mock(stdout=b'')):
                _, record, settings = backend.unit_arguments(session, {'mode': 'shell'}, None)
                self.assertTrue(record.is_file() and settings.is_file())
                backend.cleanup()
            self.assertEqual([], list(backend.records.iterdir()))


if __name__ == '__main__':
    unittest.main()
