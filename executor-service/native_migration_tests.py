"""Linux filesystem/child-process fault fixtures; systemd and native isolation are not simulated as acceptance."""
import os
import importlib.util
import json
import hashlib
from pathlib import Path
import select
import subprocess
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import migrate_native_repository as controller
import native_migration_files as files
from native_migration_guard import require_stopped, settled_state_digest


class FilesystemTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix='native-migration-')
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.source = self.root / 'copy'
        (self.source / 'work' / 'repository').mkdir(parents=True)
        (self.source / 'home').mkdir()
        (self.source / 'work' / 'repository' / 'unsaved').write_text('retain local edits')
        (self.source / 'work' / 'same').write_text('work')
        (self.source / 'home' / 'same').write_text('home')
        (self.source / 'home' / '.settings').write_text('keep')
        self.staging = self.root / 'staging'
        self.staging.mkdir(mode=0o700)

    def test_archives_preserve_distinct_roots_and_internal_hardlinks(self):
        os.link(self.source / 'work' / 'same', self.source / 'work' / 'linked')
        os.symlink('.settings', self.source / 'home' / 'settings-link')
        rows = files.inventory(self.source)
        result = files.archives(self.source, self.staging, rows, os.getuid(), os.getgid())
        self.assertEqual(set(result), {'work', 'home'})
        with tarfile.open(self.staging / 'work.tar') as archive:
            self.assertTrue(any(entry.islnk() for entry in archive.getmembers()))
            self.assertEqual(archive.extractfile('repository/unsaved').read(), b'retain local edits')
            self.assertEqual(archive.extractfile('same').read(), b'work')
        with tarfile.open(self.staging / 'home.tar') as archive:
            self.assertEqual(archive.extractfile('same').read(), b'home')
            self.assertEqual(archive.getmember('settings-link').linkname, '.settings')
        self.assertEqual(files.inventory(self.source), rows)

    def test_cross_root_hardlinks_defer_without_writing_archives(self):
        os.link(self.source / 'work' / 'same', self.source / 'home' / 'cross')
        with self.assertRaisesRegex(files.Deferred, 'CROSS_ROOT_HARDLINK'):
            files.inventory(self.source)
        self.assertEqual(list(self.staging.iterdir()), [])

    def test_outside_hardlinks_defer_without_reading_the_other_name(self):
        os.link(self.source / 'work' / 'same', self.root / 'outside')
        with self.assertRaisesRegex(files.Deferred, 'UNCAPTURED'):
            files.inventory(self.source)

    def test_special_nodes_and_source_bounds_defer(self):
        os.mkfifo(self.source / 'home' / 'fifo')
        with self.assertRaisesRegex(files.Deferred, 'UNSUPPORTED_SOURCE_NODE'):
            files.inventory(self.source)
        (self.source / 'home' / 'fifo').unlink()
        os.setxattr(self.source / 'work' / 'same', 'user.fixture', b'uncaptured metadata')
        with self.assertRaisesRegex(files.Deferred, 'UNSUPPORTED_SOURCE_ATTRIBUTES'):
            files.inventory(self.source)
        os.removexattr(self.source / 'work' / 'same', 'user.fixture')
        with self.assertRaisesRegex(files.Deferred, 'SOURCE_LIMIT'):
            files.inventory(self.source, maximum_entries=1)

    def test_prepared_source_change_never_regenerates_archives(self):
        operation = self.root / 'operation'
        operation.mkdir(mode=0o700)
        source, request = self.source, {'appUid': os.getuid(), 'appGid': os.getgid()}

        class Guard:
            source_identity = files.identity(source)
            state_sha = 'a' * 64

            def bind(self, record):
                pass

        guard = Guard()
        guard.source, guard.request = source, request
        read = lambda path: files.read_json(path, uid=os.getuid())
        write = lambda path, value: files.write_new(path, value, os.getuid(), os.getgid())
        with patch.object(controller, 'read_json', read), patch.object(controller, 'write_new', write):
            controller.stage_archives(operation, self.staging, guard, {}, False)
            original = files.file_digest(self.staging / 'work.tar')
            (source / 'work' / 'repository' / 'unsaved').write_text('new edits after prepare')
            with self.assertRaisesRegex(files.Deferred, 'SOURCE_CHANGED_SINCE_PREPARATION'):
                controller.stage_archives(operation, self.staging, guard, {}, True)
            self.assertEqual(files.file_digest(self.staging / 'work.tar'), original)
            self.assertEqual((source / 'work' / 'repository' / 'unsaved').read_text(), 'new edits after prepare')

    def test_inactive_unit_with_populated_descendants_is_not_containment(self):
        group = self.root / 'legacy.service'
        group.mkdir()
        (group / 'cgroup.events').write_text('populated 1\nfrozen 0\n')
        value = {'ActiveState': 'inactive', 'MainPID': '0', 'ControlGroup': '/legacy.service'}
        with self.assertRaisesRegex(files.Deferred, 'DESCENDANTS_ACTIVE'):
            require_stopped(value, self.root)
        (group / 'cgroup.events').write_text('populated 0\nfrozen 0\n')
        require_stopped(value, self.root)

    def test_java_keeps_source_and_real_deployment_style_flocks_after_parent_close(self):
        source = files.HeldLock(self.root / 'source.lock', create=True, uid=os.getuid())
        deployment = files.HeldLock(self.root / 'deployment.lock', create=True, uid=os.getuid())
        descriptors = [source.descriptor(), deployment.descriptor()]
        command = [os.environ['MIGRATION_TEST_JAVA'], '-cp', os.environ['MIGRATION_TEST_CLASSPATH'],
                   'io.github.core607.poketto.executor.internal.NativeMigrationLockProbe']
        for item in descriptors:
            command.extend(str(item[key]) for key in ('descriptor', 'device', 'inode'))
        child = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                 pass_fds=(source.fd, deployment.fd))
        try:
            self.assertTrue(select.select([child.stdout], [], [], 15)[0])
            self.assertEqual(child.stdout.readline(), b'inherited-locks-held\n')
            source.__exit__()
            deployment.__exit__()
            for name in ('source.lock', 'deployment.lock'):
                with self.assertRaises(BlockingIOError):
                    files.HeldLock(self.root / name, uid=os.getuid())
            child.stdin.close()
            self.assertEqual(child.wait(timeout=10), 0)
            for name in ('source.lock', 'deployment.lock'):
                with files.HeldLock(self.root / name, uid=os.getuid()):
                    pass
        finally:
            if child.poll() is None:
                child.kill()
                child.wait(timeout=10)
            for descriptor in (source.fd, deployment.fd):
                try:
                    os.close(descriptor)
                except OSError:
                    pass
            for stream in (child.stdin, child.stdout, child.stderr):
                stream.close()

    def test_existing_deployer_observes_the_same_installation_lock(self):
        parent = Path(__file__).resolve().parent
        deployment = parent / 'deploy' if (parent / 'deploy').exists() else parent.parent / 'deploy'
        sys.path.insert(0, str(deployment))
        spec = importlib.util.spec_from_file_location('migration_update_existing', deployment / 'update-existing.py')
        updater = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(updater)
        state = self.root / '.deployment'
        state.mkdir(mode=0o700)

        class Installation:
            state_dir = state

            def update(self, *args):
                raise AssertionError('Deployment bypassed the shared lock')

        args = ['update-existing.py', '--root', str(self.root), '--app-image', 'fixture/app:one',
                '--frontend-image', 'fixture/frontend:one', '--app-revision', 'a' * 40]
        with files.HeldLock(state / 'lock', create=True, uid=os.getuid()), \
                patch.object(sys, 'argv', args), patch.object(updater.os, 'geteuid', return_value=0), \
                patch.object(updater, 'load_config', return_value={}), \
                patch.object(updater, 'Installation', return_value=Installation()):
            with self.assertRaisesRegex(updater.DeploymentError, 'another deployment holds'):
                updater.main()

    def test_pending_or_unknown_deployments_cannot_authorize_copy_conversion(self):
        base = {'status': 'healthy', 'revision': 'a' * 40, 'configuration': 'b' * 64,
                'imageIds': {'app': 'sha256:' + 'c' * 64, 'frontend': 'sha256:' + 'd' * 64}}
        raw = json.dumps(base, indent=2).encode()
        self.assertEqual(settled_state_digest(raw), hashlib.sha256(raw).hexdigest())
        for change in ({'status': 'pending'}, {'schemaVersion': 3}, {'nativeDependency': None}):
            with self.assertRaises(files.Deferred):
                settled_state_digest(json.dumps({**base, **change}).encode())


if __name__ == '__main__':
    unittest.main(verbosity=2)
