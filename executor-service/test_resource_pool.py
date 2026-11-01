from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from resource_pool import PoolUnavailable, ResourcePool, check_service, limits, slice_name


class ResourcePoolTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.values = {'memory.max': '100000000', 'memory.swap.max': '0',
                       'pids.max': '64', 'cpu.max': '50000 100000', 'cgroup.controllers': 'cpu memory pids'}
        for name, value in self.values.items():
            (self.directory / name).write_text(value)

    def test_finite_kernel_limits_allow_zero_swap(self):
        self.assertEqual({'memory.max': 100000000, 'memory.swap.max': 0,
                          'pids.max': 64, 'cpu.max': (50000, 100000)}, limits(self.directory))

    def test_missing_unlimited_or_malformed_budget_is_rejected(self):
        for name in ('memory.max', 'memory.swap.max', 'pids.max', 'cpu.max'):
            path = self.directory / name
            invalid = ['max', '-1', '9223372036854775808', 'invalid']
            invalid += ['max 100000', '0 100000', '50000 0', '1 2 3'] if name == 'cpu.max' else []
            invalid += ['0'] if name in ('memory.max', 'pids.max') else []
            for value in invalid:
                with self.subTest(name=name, value=value):
                    path.write_text(value)
                    with self.assertRaises(PoolUnavailable):
                        limits(self.directory)
            path.unlink()
            with self.assertRaises(OSError):
                limits(self.directory)
            path.write_text(self.values[name])

    def test_only_dedicated_slice_names_are_accepted(self):
        for name in (None, '', '-.slice', 'system.slice', 'user.slice', 'machine.slice',
                     '../a.slice', 'a.service', 'a.slice\n', 'a/b.slice', 'a' * 241 + '.slice'):
            with self.subTest(name=name), self.assertRaises(PoolUnavailable):
                slice_name(name)
        self.assertEqual('poketto-executor.slice', slice_name('poketto-executor.slice'))

    @patch('resource_pool.properties')
    def test_hierarchical_slice_uses_actual_path_and_rejects_siblings(self, properties):
        properties.return_value = {'ActiveState': 'active',
                                   'ControlGroup': '/poketto.slice/poketto-executor.slice'}
        pool = ResourcePool('poketto-executor.slice')
        self.assertEqual(Path('/sys/fs/cgroup/poketto.slice/poketto-executor.slice'), pool.directory)
        pool.directory = self.directory
        pool.verify('/poketto.slice/poketto-executor.slice/worker.service')
        for current in ('/system.slice/worker.service', '/poketto.slice/poketto-executor.slice-other/worker.service',
                        '/poketto.slice/poketto-executor.slice', '/poketto.slice/../worker.service'):
            with self.subTest(current=current), self.assertRaises(PoolUnavailable):
                pool.verify(current)
        (self.directory / 'memory.max').write_text('max')
        with self.assertRaises(PoolUnavailable):
            pool.verify('/poketto.slice/poketto-executor.slice/worker.service')

    @patch('resource_pool.properties')
    def test_missing_inactive_or_wrong_slice_cgroup_is_rejected(self, properties):
        for result in ({}, {'ActiveState': 'inactive'}, {'ActiveState': 'active', 'ControlGroup': '/system.slice'},
                       {'ActiveState': 'active', 'ControlGroup': '/'}):
            properties.return_value = result
            with self.subTest(result=result), self.assertRaises(PoolUnavailable):
                ResourcePool('poketto-executor.slice')

    @patch('resource_pool.ResourcePool')
    @patch('resource_pool.process_group')
    @patch('resource_pool.properties')
    @patch('resource_pool.Path.stat')
    @patch('resource_pool.Path.read_text', return_value='-900')
    def test_deploy_checks_actual_root_main_process_and_service_membership(self, score, stat, properties, process, pool):
        stat.return_value.st_uid = 0
        properties.return_value = {'ActiveState': 'active', 'User': 'root', 'MainPID': '42',
                                   'OOMScoreAdjust': '-900', 'OOMPolicy': 'stop',
                                   'Slice': 'poketto-executor.slice',
                                   'ControlGroup': '/poketto.slice/poketto-executor.slice/worker.service'}
        process.return_value = properties.return_value['ControlGroup']
        check_service('worker.service')
        pool.assert_called_once_with('poketto-executor.slice')
        pool.return_value.verify.assert_called_once_with(process.return_value)
        for field, invalid in [('ActiveState', 'inactive'), ('User', 'nobody'), ('MainPID', '0'),
                               ('ControlGroup', '/system.slice/other.service')]:
            old = properties.return_value[field]
            properties.return_value[field] = invalid
            with self.subTest(field=field), self.assertRaises(PoolUnavailable):
                check_service('worker.service')
            properties.return_value[field] = old
        stat.return_value.st_uid = 1000
        with self.assertRaises(PoolUnavailable):
            check_service('worker.service')

if __name__ == '__main__':
    unittest.main()
