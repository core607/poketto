"""Offline identity and evidence checks; these tests never create services or claim containment."""
import json
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import Mock, patch
import uuid

from native_game_java_probe import CHECKS, GameFixture, acknowledge, check_java_and_bound_controller, overlap, read_result, request


class NativeGameControllerTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.token = str(uuid.uuid4())
        self.report = self.root / 'report.json'

    def result(self):
        return {'result': 'PASS', 'game': {'gameNative': 'PASS', 'checks': [
            {'test': name, 'result': 'PASS'} for name in CHECKS]},
            'repository': {'test': 'native-repository-copy-and-discard-through-public-service',
                           'result': 'PASS', 'copyId': str(uuid.uuid4()), 'commit': 'a' * 40, 'reads': 3}}

    def test_game_assets_remain_readable_to_the_app_under_a_private_controller_umask(self):
        source = self.root / 'source'
        source.mkdir()
        for name in ('sandcrab', 'bwrap', 'bwrap-notices.txt', 'bwrap-source.tar.xz'):
            (source / name).write_bytes(b'fixture source, never executed')
        (source / 'game-example.mjs').write_text('export default {};')
        app = SimpleNamespace(pw_uid=os.getuid(), pw_gid=os.getgid())
        previous = os.umask(0o077)
        try:
            fixture = GameFixture(self.root, source, self.root / 'tools', app, 'repository', 'repository.slice')
        finally:
            os.umask(previous)
        self.addCleanup(fixture.listener.server_close)
        self.addCleanup(fixture.listener.shutdown)
        self.assertEqual(stat.S_IMODE(fixture.assets.stat().st_mode), 0o755)
        asset = fixture.assets / 'game-example.mjs'
        self.assertEqual(stat.S_IMODE(asset.stat().st_mode), 0o644)
        self.assertEqual(asset.read_text(), 'export default {};')

    def test_java_version_runs_before_the_controller_address_space_limit(self):
        order = []
        def version(arguments, **options):
            self.assertEqual(arguments, ['/fixture/java', '-version'])
            self.assertEqual(order, [])
            self.assertEqual(options['timeout'], 20)
            order.append('java')
            return SimpleNamespace(stderr='openjdk version "26.0.2"\n')
        with patch('native_game_java_probe.subprocess.run', side_effect=version), \
                patch('native_game_java_probe.resource.setrlimit', side_effect=lambda *args: order.append('limit')):
            check_java_and_bound_controller(Path('/fixture/java'))
        self.assertEqual(order, ['java', 'limit'])

    def test_collected_game_unit_is_distinguished_from_a_live_unit_or_unavailable_manager(self):
        fixture = object.__new__(GameFixture)
        fixture.supervisor = 'fixture-game.service'
        for state, accepted in [('not-found', True), ('loaded', False), ('', False)]:
            with self.subTest(state=state), patch('game_fixture.subprocess.run',
                    return_value=subprocess.CompletedProcess(['systemctl', 'stop'], 5)), \
                    patch('game_fixture.run', return_value=state):
                if accepted:
                    fixture.stop_worker()
                else:
                    with self.assertRaises(subprocess.CalledProcessError):
                        fixture.stop_worker()

    def test_restart_requires_confirmed_unit_stop_and_descendant_containment_before_replacement(self):
        fixture = object.__new__(GameFixture)
        fixture.supervisor = 'fixture-game.service'
        order = []
        fixture.stop_worker = Mock(side_effect=lambda: order.append('stopped'))
        fixture.no_processes = Mock(side_effect=lambda: order.append('contained'))
        fixture.start_worker = Mock(side_effect=lambda: order.append('started'))
        with patch('game_fixture.run', side_effect=lambda args: order.append('killed')), \
                patch('game_fixture.subprocess.run'):
            fixture.control('game-restart-worker')
            self.assertEqual(order, ['killed', 'stopped', 'contained', 'started'])
            fixture.start_worker.reset_mock()
            fixture.stop_worker.side_effect = AssertionError('manager unavailable')
            with self.assertRaisesRegex(AssertionError, 'manager unavailable'):
                fixture.control('game-restart-worker')
            fixture.start_worker.assert_not_called()

    def test_restart_waits_for_the_old_delegated_cgroup_after_unit_collection(self):
        fixture = object.__new__(GameFixture)
        fixture.supervisor = 'fixture-game'
        fixture.pool = SimpleNamespace(name='fixture.slice')
        fixture.binary = self.root / 'sandcrab'
        fixture.socket = self.root / 'control.sock'
        fixture.config_path = self.root / 'config.toml'
        fixture.app = SimpleNamespace(pw_name='fixture-app')
        calls = []
        def command(args):
            calls.append(args[0])
            if args[0] == 'systemctl':
                return 'not-found'
            if args[0] == 'runuser':
                return json.dumps({'profiles': {'games': {}}, 'capabilities': []})
            return ''
        with patch('game_fixture.run', side_effect=command), patch('game_fixture.Path.exists', side_effect=[True, False]), \
                patch('game_fixture.Path.is_socket', return_value=True), patch('game_fixture.time.sleep'), \
                patch('game_fixture.userns_service_args', return_value=[]):
            fixture.start_worker()
        self.assertEqual(calls, ['systemctl', 'systemctl', 'systemd-run', 'runuser'])

    def test_actual_java26_child_starts_before_its_python_parent_is_bounded(self):
        java = shutil.which('java')
        self.assertIsNotNone(java, 'The offline regression needs the pinned Java 26 runtime')
        source = '''import json,resource,sys
from native_game_java_probe import check_java_and_bound_controller
check_java_and_bound_controller(sys.argv[1])
print(json.dumps(resource.getrlimit(resource.RLIMIT_AS)))
'''
        result = subprocess.run([sys.executable, '-B', '-c', source, java], check=True, capture_output=True,
                                text=True, timeout=25, cwd=Path(__file__).resolve().parent)
        self.assertEqual(json.loads(result.stdout), [256 * 1024 * 1024] * 2)

    def test_exact_fixture_request_is_acknowledged_without_unit_names(self):
        value = {'token': self.token, 'sequence': 1, 'action': 'game-restart-worker'}
        (self.root / 'request-1.json').write_text(json.dumps(value))
        self.assertEqual(value, request(self.root, self.token, 1))
        acknowledge(self.root, value)
        self.assertEqual(value, json.loads((self.root / 'ack-1.json').read_text()))
        with self.assertRaises(AssertionError):
            acknowledge(self.root, value)

    def test_changed_identity_sequence_action_or_extra_unit_is_rejected(self):
        for field, change in [('token', str(uuid.uuid4())), ('sequence', 2),
                              ('sequence', True), ('action', 'stop production'), ('unit', 'other.service')]:
            with self.subTest(field=field, change=change):
                value = {'token': self.token, 'sequence': 1, 'action': 'game-restart-worker', field: change}
                (self.root / 'request-1.json').write_text(json.dumps(value))
                with self.assertRaises(AssertionError):
                    request(self.root, self.token, 1)

    def test_oversized_control_request_is_rejected(self):
        (self.root / 'request-1.json').write_bytes(b'x' * 4097)
        with self.assertRaises(AssertionError):
            request(self.root, self.token, 1)

    def test_complete_result_requires_all_game_checks_and_native_repository(self):
        value = self.result()
        raw = json.dumps(value).encode()
        (self.root / 'result.json').write_bytes(raw)
        self.assertEqual(value, read_result(self.root, self.report))
        self.assertEqual(raw, self.report.with_suffix('.java.json').read_bytes())

    def test_diagnostic_stdout_cannot_substitute_for_a_structured_result(self):
        self.report.with_suffix('.stdout').write_text(json.dumps(self.result()))
        with self.assertRaises(AssertionError):
            read_result(self.root, self.report)

    def test_missing_duplicate_failed_game_check_or_fake_repository_proof_is_rejected(self):
        for change in ('missing', 'duplicate', 'failed', 'repository'):
            with self.subTest(change=change):
                value = self.result()
                checks = value['game']['checks']
                if change == 'missing':
                    checks.pop()
                elif change == 'duplicate':
                    checks[0] = checks[1]
                elif change == 'failed':
                    checks[0]['result'] = 'FAIL'
                else:
                    value['repository']['reads'] = 0
                (self.root / 'result.json').write_text(json.dumps(value))
                with self.assertRaises(AssertionError):
                    read_result(self.root, self.root / (change + '.json'))

    def test_malformed_result_is_retained_and_previous_evidence_cannot_be_overwritten(self):
        raw = b'{"result":"PASS"}\n{"result":"FAIL"}'
        (self.root / 'result.json').write_bytes(raw)
        with self.assertRaises(json.JSONDecodeError):
            read_result(self.root, self.report)
        self.assertEqual(raw, self.report.with_suffix('.java.json').read_bytes())
        (self.root / 'result.json').write_text(json.dumps(self.result()))
        with self.assertRaises(FileExistsError):
            read_result(self.root, self.report)

    def test_overlap_requires_two_live_identities_in_different_native_pools(self):
        repository = SimpleNamespace(execution_user='repo', resource_slice='repo.slice', unit='repo-unit')
        game = SimpleNamespace(user='game', pool=SimpleNamespace(name='game.slice'), supervisor='game-unit')
        left, right = {'uid': 10, 'cgroup': '/repo.slice/one'}, {'uid': 11, 'cgroup': '/game.slice/two'}
        with patch('native_game_java_probe.owned_process', side_effect=[left, right]), \
                patch('native_game_java_probe.still_live', return_value=True):
            self.assertTrue(overlap('repo', repository, game)['observedTogether'])
        with patch('native_game_java_probe.owned_process', side_effect=[left, {**right, 'uid': 10}]), \
                patch('native_game_java_probe.still_live', return_value=True):
            with self.assertRaises(AssertionError):
                overlap('repo', repository, game)

    def test_two_sequential_process_observations_do_not_establish_overlap(self):
        repository = SimpleNamespace(execution_user='repo', resource_slice='repo.slice', unit='repo-unit')
        game = SimpleNamespace(user='game', pool=SimpleNamespace(name='game.slice'), supervisor='game-unit')
        with patch('native_game_java_probe.time.monotonic', side_effect=[0, 0, 5]), \
                patch('native_game_java_probe.time.sleep'), \
                patch('native_game_java_probe.owned_process', side_effect=[{'uid': 10}, {'uid': 11}]), \
                patch('native_game_java_probe.still_live', return_value=False):
            with self.assertRaisesRegex(AssertionError, 'not observed together'):
                overlap('repo', repository, game)


if __name__ == '__main__':
    unittest.main()
