"""Owned Sandcrab service and bounded durable volumes for real application acceptance."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time
import uuid

POOL_BYTES = 512 * 1024 * 1024
COPY_BYTES = 64 * 1024 * 1024
COPY_INODES = 8192
TRANSFER_BYTES = 16 * 1024 * 1024


def run(arguments, **options):
    return subprocess.run(arguments, check=True, capture_output=True, text=True, timeout=60, **options).stdout.strip()


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def stop_unit(unit):
    subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=40)
    state = subprocess.run(['systemctl', 'show', unit, '-p', 'LoadState', '-p', 'ActiveState'],
                           capture_output=True, text=True, timeout=10)
    fields = dict(line.split('=', 1) for line in state.stdout.splitlines() if '=' in line)
    assert fields.get('LoadState') in ('loaded', 'not-found')
    assert fields.get('ActiveState') in ('inactive', 'failed'), 'Owned fixture unit survived cleanup'
    subprocess.run(['systemctl', 'reset-failed', unit], capture_output=True, timeout=10)


def verify_runtime(runtime):
    expected = {}
    for line in (runtime / 'manifest.sha256').read_text().splitlines():
        digest, relative = line.split('  ', 1)
        name = Path(relative)
        assert not name.is_absolute() and '..' not in name.parts and relative not in expected
        target = runtime / name
        assert target.is_file() and not target.is_symlink() and target.resolve().is_relative_to(runtime)
        assert sha(target) == digest, 'Acceptance runtime differs from its build manifest'
        expected[relative] = digest
    paths = list(runtime.rglob('*'))
    assert all(not path.is_symlink() for path in paths), 'Acceptance runtime cannot contain symbolic links'
    assert expected.keys() == {path.relative_to(runtime).as_posix() for path in paths
                               if path.is_file() and path != runtime / 'manifest.sha256'}
    return sha(runtime / 'manifest.sha256')


def configuration(root, tools, app, execution_user, unit, resource_slice, agents):
    assert 1 <= agents <= 64
    slots = max(4, agents)
    connections = min(256, max(32, 4 * agents + 8))
    return f'''[service]
runtime_root = {json.dumps(str(root / 'native-runtime'))}
socket = {json.dumps(str(root / 'native-runtime/control.sock'))}
client_uid = {app.pw_uid}
client_gid = {app.pw_gid}
resource_slice = {json.dumps(resource_slice)}
supervisor_unit = {json.dumps(unit + '.service')}
unit_prefix = {json.dumps(unit + '-')}
max_sandboxes = {slots}
max_records = 1024
max_output_bytes = 16777216
max_file_records = 1024
max_file_transfers = {min(16, slots)}
max_connections = {connections}
max_event_streams = {min(128, connections - 4, 2 * slots)}
[profiles.repository]
exec_user = {json.dumps(execution_user)}
tools = {json.dumps(str(tools))}
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache"]
memory_bytes = 201326592
temporary_bytes = 8388608
temporary_inodes = 1024
tasks_max = 48
cpu_quota_percent = 50
timeout_millis = 60000
session_ttl_millis = 60000
output_limit_bytes = 1048576
transfer_bytes = {TRANSFER_BYTES}
network = "none"
host_calls = true
[storage]
root = {json.dumps(str(root / 'native-storage'))}
pool_bytes = {POOL_BYTES}
max_volume_bytes = {COPY_BYTES}
max_volume_inodes = {COPY_INODES}
max_volumes = {max(16, 2 * slots)}
max_records = 1024
'''


class NativeRepositoryFixture:
    def __init__(self, root, binary, tools, app, execution_user, resource_slice, token, agents):
        self.root, self.app, self.execution_user = root, app, execution_user
        self.unit = 'sandcrab-client-' + token
        self.resource_slice = resource_slice
        self.directory = root / 'native-service'
        self.binary = self.directory / 'sandcrab'
        self.socket = root / 'native-runtime/control.sock'
        self.storage = root / 'native-storage'
        self.data = self.storage / 'data'
        self.image = root / 'native-volumes.img'
        self.config = root / 'native-service.toml'
        self.mounted = False
        self.contained = False
        self.directory.mkdir(mode=0o755)
        self.directory.chmod(0o755)
        shutil.copyfile(binary, self.binary)
        self.binary.chmod(0o755)
        assert sha(binary) == sha(self.binary)
        self.config.write_text(configuration(root, tools, app, execution_user, self.unit, resource_slice, agents))
        self.config.chmod(0o600)

    def command(self, *arguments):
        return run(['runuser', '-u', self.app.pw_name, '--', str(self.binary), *arguments,
                    '--socket', str(self.socket)])

    def start(self):
        self.storage.mkdir(mode=0o755)
        self.storage.chmod(0o755)
        self.data.mkdir(mode=0o755)
        self.data.chmod(0o755)
        with self.image.open('xb') as image:
            image.truncate(POOL_BYTES)
        run(['mkfs.xfs', '-f', '-m', 'reflink=1', str(self.image)])
        run(['mount', '-o', 'loop,prjquota,nosuid,nodev', str(self.image), str(self.data)])
        self.mounted = True
        run([str(self.binary), 'check', '--config', str(self.config)])
        run(['systemd-run', '--quiet', '--collect', '--unit', self.unit, '--slice', self.resource_slice,
             '-p', 'User=root', '-p', 'UMask=0077', '-p', 'MemoryMax=128M', '-p', 'MemorySwapMax=0',
             '-p', 'TasksMax=128', '-p', 'KillMode=control-group', '-p', 'TimeoutStopSec=30',
             str(self.binary), 'serve', '--config', str(self.config)])
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if self.socket.is_socket():
                info = json.loads(self.command('info'))
                assert set(info['profiles']) == {'repository'} and info['profiles']['repository']['hostCalls']
                assert {'sessions', 'volumes', 'files', 'calls'} <= set(info['capabilities'])
                assert info['storage']['maxVolumeBytes'] == COPY_BYTES
                return info
            assert run(['systemctl', 'show', self.unit, '-p', 'ActiveState', '--value']) not in ('inactive', 'failed')
            time.sleep(.1)
        raise AssertionError('Native repository fixture did not become ready')

    def stop(self):
        stop_unit(self.unit)
        units = run(['systemctl', 'list-units', '--all', '--plain', '--no-legend', self.unit + '-*.service'])
        for line in units.splitlines():
            unit = line.split()[0]
            assert unit.startswith(self.unit + '-') and unit.endswith('.service')
            stop_unit(unit)
        group = run(['systemctl', 'show', self.resource_slice, '-p', 'ControlGroup', '--value'])
        if group:
            assert group.startswith('/') and '..' not in Path(group).parts
            for path in (Path('/sys/fs/cgroup') / group.lstrip('/')).rglob('cgroup.procs'):
                assert not path.read_text().strip(), 'Fixture pool still owns processes'
        subprocess.run(['systemctl', 'reset-failed', self.unit], capture_output=True, timeout=10)
        self.contained = True

    def unmount(self):
        assert self.contained, 'Native process containment must precede fixture unmounts'
        jobs = self.socket.parent / 'jobs'
        mounts = [line.split()[4] for line in Path('/proc/self/mountinfo').read_text().splitlines()
                  if line.split()[4].startswith(str(self.root) + '/native-')]
        for value in sorted(mounts, key=len, reverse=True):
            path = Path(value)
            if path != self.data:
                assert path.parent == jobs and not path.is_symlink()
                assert str(uuid.UUID(path.name)) == path.name
            run(['umount', str(path)])
        self.mounted = False
        assert not any(line.split()[4].startswith(str(self.root) + '/native-')
                       for line in Path('/proc/self/mountinfo').read_text().splitlines())
