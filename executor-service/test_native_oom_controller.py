"""Control-flow fixtures exercise fixed attempts and uncertain submissions, without inducing OOM."""
from pathlib import Path
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

NATIVE = Path(__file__).parent / 'rust/sandcrab'
sys.path.insert(0, str(NATIVE))
try:
    import oom_probe
finally:
    sys.path.pop(0)


class NativeOomControllerTests(unittest.TestCase):
    def fixture(self):
        probe = object.__new__(oom_probe.OomProbe)
        probe.info = {'serverId': 'fixture-epoch'}
        probe.sessions = [{'id': 'fixture-session'}]
        probe.socket = Path('/fixture/socket')
        probe.service_group = Path('/sys/fs/cgroup/fixture.slice/native.service')
        probe.pool_group = probe.service_group.parent
        item = {'epoch': 'fixture-epoch', 'main': {'pid': 42}}
        for name in ('recover', 'contained', 'evidence', 'save', 'done'):
            setattr(probe, name, Mock())
        probe.begin = Mock(return_value=item)
        return probe, item

    def test_daemon_pressure_has_one_fixed_limit(self):
        probe, item = self.fixture()
        probe.retained_output = Mock(return_value='x' * oom_probe.OUTPUT_BYTES)
        probe.daemon_oom()
        self.assertEqual(64, probe.retained_output.call_count)
        self.assertEqual(64, item['pressure']['completed'])
        probe.contained.assert_called_once()
        probe.evidence.assert_called_once_with(item, probe.service_group, required=42)

    def test_lost_submission_is_observed_without_sending_the_next_one(self):
        probe, item = self.fixture()
        probe.retained_output = Mock(side_effect=ConnectionResetError('uncertain outcome'))
        probe.daemon_oom()
        probe.retained_output.assert_called_once()
        self.assertEqual(0, item['pressure']['completed'])
        probe.contained.assert_called_once()
        probe.evidence.assert_called_once()

    @patch('oom_probe.http', side_effect=ConnectionResetError('lost reply'))
    def test_submission_identity_is_durable_before_the_request(self, http):
        probe, _ = self.fixture()
        item = {'pressure': {'submittedIds': []}}
        def verify_saved():
            self.assertEqual(1, len(item['pressure']['submittedIds']))
            http.assert_not_called()
        probe.save.side_effect = verify_saved
        with self.assertRaises(ConnectionResetError):
            probe.retained_output('fixture command', item)
        probe.save.assert_called_once()
        http.assert_called_once()
        self.assertEqual(item['pressure']['submittedIds'][0], http.call_args.args[3]['id'])

    def test_failed_containment_never_claims_recovery(self):
        probe, _ = self.fixture()
        probe.retained_output = Mock(side_effect=ConnectionResetError('lost reply'))
        probe.contained.side_effect = AssertionError('live descendant')
        with self.assertRaisesRegex(AssertionError, 'live descendant'):
            probe.daemon_oom()
        probe.evidence.assert_not_called()
        self.assertEqual(1, probe.recover.call_count)  # Initial fixed-budget startup only.

    @patch('oom_probe.call')
    @patch('oom_probe.counters', return_value={'oom': 1, 'oom_kill': 1})
    def test_mismatched_kernel_victim_preserves_observations_without_passing(self, counters, call):
        probe, _ = self.fixture()
        probe.evidence = oom_probe.OomProbe.evidence.__get__(probe)
        item = {'before': {'oom': 0}, 'poolBefore': {'oom_kill': 0}, 'cursor': 'fixture-cursor',
                'cgroup': '/fixture.slice/native.service', 'result': 'RUNNING'}
        line = ('oom-kill:constraint=CONSTRAINT_MEMCG,oom_memcg=/fixture.slice/native.service,'
                'task_memcg=/fixture.slice/native.service,task=helper,pid=43,uid=0')
        call.return_value = SimpleNamespace(stdout=line)
        with self.assertRaises(AssertionError):
            probe.evidence(item, probe.service_group, required=42)
        self.assertEqual([line], item['kernelOom'])
        self.assertEqual(1, item['poolAfter']['oom_kill'])
        self.assertNotIn('kernelResult', item)
        self.assertEqual('RUNNING', item['result'])
        self.assertEqual(2, probe.save.call_count)

    def unit_fixture(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        root = Path(directory.name)
        probe, _ = self.fixture()
        probe.unit = 'fixture-native.service'
        probe.slice = 'fixture.slice'
        probe.binary = root / 'sandcrab'
        probe.config = root / 'config.toml'
        probe.unit_file = root / probe.unit
        probe.unit_definition = b'[Service]\nMemoryMax=33554432\nOOMPolicy=continue\n'
        probe.unit_file.write_bytes(probe.unit_definition)
        probe.unit_identity = probe.unit_file.stat()
        probe.service_bytes = oom_probe.SERVICE_BYTES
        probe.policy = 'stop'
        probe.wait_ready = Mock()
        probe.guard = Mock()
        return probe

    @patch('oom_probe.call')
    @patch('oom_probe.properties', return_value={'MainPID': '0', 'ActiveState': 'inactive', 'LoadState': 'loaded'})
    def test_loaded_old_unit_references_do_not_keep_the_previous_phase(self, properties, call):
        probe = self.unit_fixture()
        probe.start()
        definition = probe.unit_file.read_text()
        self.assertIn('MemoryMax=100663296\n', definition)
        self.assertIn('OOMPolicy=stop\n', definition)
        self.assertEqual([('systemctl', 'daemon-reload'), ('systemctl', 'start', probe.unit)],
                         [item.args for item in call.call_args_list])
        probe.guard.assert_called_once()
        probe.service_bytes = oom_probe.LOCAL_BYTES
        probe.policy = 'continue'
        probe.start()
        self.assertIn('MemoryMax=33554432\n', probe.unit_file.read_text())
        self.assertIn('OOMPolicy=continue\n', probe.unit_file.read_text())

    @patch('oom_probe.call')
    @patch('oom_probe.properties')
    def test_live_or_external_unit_changes_refuse_before_reloading(self, properties, call):
        probe = self.unit_fixture()
        for state in ({'MainPID': '42', 'ActiveState': 'active'}, {'MainPID': '0', 'ActiveState': 'activating'}):
            properties.return_value = state
            with self.subTest(state=state), self.assertRaises(AssertionError):
                probe.start()
        properties.return_value = {'MainPID': '0', 'ActiveState': 'inactive'}
        probe.unit_file.write_bytes(b'changed by another owner')
        with self.assertRaises(AssertionError):
            probe.start()
        call.assert_not_called()

    @patch('oom_probe.time.sleep')
    @patch('oom_probe.kernel_window')
    def test_late_journal_line_only_reobserves_the_same_cursor(self, window, sleep):
        probe, _ = self.fixture()
        item = {'cursor': 'fixture-cursor', 'cgroup': '/fixture.slice/native.service'}
        line = ('oom-kill:constraint=CONSTRAINT_MEMCG,oom_memcg=/fixture.slice/native.service,'
                'task_memcg=/fixture.slice/native.service,task=sandcrab,pid=42,uid=0')
        window.side_effect = ['', line]
        self.assertEqual(42, probe.kernel_evidence(item, 42, None)[0]['pid'])
        self.assertEqual([('fixture-cursor',), ('fixture-cursor',)], [call.args for call in window.call_args_list])
        sleep.assert_called_once_with(.05)
        self.assertEqual([line], item['kernelOom'])

    def session_fixture(self):
        probe = self.unit_fixture()
        probe.pool_group = probe.unit_file.parent / 'pool'
        group = probe.pool_group / 'session.slice'
        group.mkdir(parents=True)
        (group / 'cgroup.procs').write_text('')
        probe.session_groups = {'session': group}
        probe.socket = probe.unit_file.parent / 'runtime/control.sock'
        session = {'id': 'session', 'serverId': 'epoch', 'profile': 'python', 'volumeId': 'volume'}
        result = {**session, 'state': 'failed', 'activeRunId': None, 'code': None, 'parentSessionId': None}
        volume = {'id': 'volume', 'profile': 'python', 'state': 'ready', 'busy': False, 'attachedTo': None}
        probe.session_wait = Mock(return_value=result)
        probe.session_forget = Mock()
        return probe, session, result, volume

    @patch('oom_probe.http')
    def test_parent_oom_failed_session_is_forgotten_only_after_release_checks(self, http):
        probe, session, result, volume = self.session_fixture()
        item = {}
        http.side_effect = [(200, result), (200, volume)]
        def forgetting(expected):
            self.assertEqual(session, expected)
            self.assertTrue(item['sessionReleases'][0]['contained'])
            self.assertEqual(volume, item['sessionReleases'][0]['volume'])
        probe.session_forget.side_effect = forgetting
        probe.release_pressure_session(session, item)
        probe.session_forget.assert_called_once_with(session)
        self.assertEqual(['POST', 'GET'], [call.args[1] for call in http.call_args_list])

    @patch('oom_probe.http')
    def test_unconfirmed_or_still_attached_pressure_sessions_are_never_forgotten(self, http):
        for fault in ('closing', 'activeRunId', 'serverId', 'cgroup', 'workspace', 'attachment'):
            with self.subTest(fault=fault):
                probe, session, result, volume = self.session_fixture()
                if fault == 'closing':
                    result['state'] = 'closing'
                elif fault == 'activeRunId':
                    result['activeRunId'] = 'run'
                elif fault == 'serverId':
                    result['serverId'] = 'another-epoch'
                elif fault == 'cgroup':
                    (probe.session_groups['session'] / 'cgroup.procs').write_text('42\n')
                elif fault == 'workspace':
                    (probe.socket.parent / 'jobs/session').mkdir(parents=True)
                else:
                    volume['attachedTo'] = 'session'
                http.side_effect = [(200, result), (200, volume)]
                with self.assertRaises(AssertionError):
                    probe.release_pressure_session(session, {})
                probe.session_forget.assert_not_called()


if __name__ == '__main__':
    unittest.main()
