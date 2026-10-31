"""Classifier fixtures do not replace a real native/kernel OOM receipt."""
import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch

from resource_pool import PoolUnavailable, check_service

SPEC = importlib.util.spec_from_file_location('oom_evidence', Path(__file__).parent / 'rust/sandcrab/oom_evidence.py')
evidence = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(evidence)
POOL = '/fixture.slice'
SERVICE = POOL + '/native.service'


def kernel(group=SERVICE, pid=42, task_group=None, constraint='CONSTRAINT_MEMCG'):
    return (f'oom-kill:constraint={constraint},nodemask=(null),cpuset=/,mems_allowed=0,'
            f'oom_memcg={group},task_memcg={task_group or group},task=sandcrab,pid={pid},uid=0')


class NativeOomEvidenceTests(unittest.TestCase):
    def test_daemon_and_parent_scope_require_exact_kernel_victim(self):
        self.assertEqual(42, evidence.kernel_victims(kernel(), SERVICE, required_pid=42)[0]['pid'])
        self.assertEqual(43, evidence.kernel_victims(kernel(POOL, 43, POOL + '/child.service'), POOL,
                                                   forbidden_pid=42)[0]['pid'])

    def test_signal_or_other_cgroup_is_not_oom_proof(self):
        for lines in ('exit=137', 'Result=oom-kill', kernel(SERVICE + '-other'),
                      kernel(task_group='/outside.service'), kernel(pid=43)):
            with self.subTest(lines=lines), self.assertRaises(AssertionError):
                evidence.kernel_victims(lines, SERVICE, required_pid=42)

    def test_member_victim_does_not_prove_policy_if_main_was_also_killed(self):
        self.assertEqual(43, evidence.kernel_victims(kernel(pid=43), SERVICE,
                                                   required_pid=43, forbidden_pid=42)[0]['pid'])
        with self.assertRaises(AssertionError):
            evidence.kernel_victims(kernel(pid=43) + '\n' + kernel(), SERVICE,
                                    required_pid=43, forbidden_pid=42)

    def test_global_oom_invalidates_an_otherwise_valid_scope(self):
        with self.assertRaises(AssertionError):
            evidence.kernel_victims(kernel() + '\n' + kernel('/outside', constraint='CONSTRAINT_NONE'), SERVICE)

    def test_parent_local_cause_and_hierarchical_victim_are_distinct(self):
        evidence.increased({'oom_kill': 1}, {'oom_kill': 2}, {'oom': 0, 'oom_kill': 0}, {'oom': 1, 'oom_kill': 0})
        evidence.increased({'oom_kill': 1}, {'oom_kill': 2}, {'oom': 0}, None)
        for after, local in (({'oom_kill': 1}, {'oom': 1}), ({'oom_kill': 2}, {'oom': 0})):
            with self.subTest(after=after), self.assertRaises(AssertionError):
                evidence.increased({'oom_kill': 1}, after, {'oom': 0}, local)

    def test_protected_relays_and_unprotected_independent_units(self):
        main = {'cgroup': SERVICE, 'oomScoreAdjust': -900}
        relays = [{'exe': 'systemd-run', **main}]
        guests = [{'cgroup': POOL + '/session.slice/child.service', 'oomScoreAdjust': 0}]
        evidence.process_topology(main, relays, guests, SERVICE, POOL)
        for change in ({'cgroup': SERVICE}, {'oomScoreAdjust': -900}):
            with self.subTest(change=change), self.assertRaises(AssertionError):
                evidence.process_topology(main, relays, [{**guests[0], **change}], SERVICE, POOL)
        with self.assertRaises(AssertionError):
            evidence.process_topology(main, [], guests, SERVICE, POOL)

    def test_continue_is_an_explicit_negative_control_only(self):
        values = {'OOMPolicy': 'stop', 'OOMScoreAdjust': '-900', 'KillMode': 'control-group'}
        evidence.unit_policy(values, '-900')
        values['OOMPolicy'] = 'continue'
        with self.assertRaises(AssertionError):
            evidence.unit_policy(values, '-900')
        evidence.unit_policy(values, '-900', policy='continue')
        with self.assertRaises(AssertionError):
            evidence.unit_policy(values, '0', policy='continue')


class NativeInstalledPolicyTests(unittest.TestCase):
    @patch('resource_pool.ResourcePool')
    @patch('resource_pool.process_group', return_value=SERVICE)
    @patch('resource_pool.Path.stat')
    @patch('resource_pool.properties')
    @patch('resource_pool.Path.read_text', return_value='-900')
    def test_missing_or_wrong_supervision_policy_is_rejected(self, score, properties, stat, process, pool):
        stat.return_value.st_uid = 0
        valid = {'ActiveState': 'active', 'User': 'root', 'MainPID': '42', 'Slice': 'fixture.slice',
                 'ControlGroup': SERVICE, 'OOMScoreAdjust': '-900', 'OOMPolicy': 'stop'}
        properties.return_value = valid
        check_service('native.service')
        pool.return_value.verify.assert_called_once_with(SERVICE)
        for field, bad in (('OOMScoreAdjust', '0'), ('OOMScoreAdjust', '-1000'),
                           ('OOMPolicy', 'continue'), ('OOMPolicy', 'kill')):
            with self.subTest(field=field, value=bad), self.assertRaises(PoolUnavailable):
                properties.return_value = {**valid, field: bad}
                check_service('native.service')
        for field in ('OOMPolicy', 'OOMScoreAdjust'):
            with self.subTest(missing=field), self.assertRaises(PoolUnavailable):
                properties.return_value = {key: value for key, value in valid.items() if key != field}
                check_service('native.service')
        properties.return_value = valid
        score.return_value = '0'
        with self.assertRaises(PoolUnavailable):
            check_service('native.service')


if __name__ == '__main__':
    unittest.main()
