"""Own the released service used by Poketto's Java transport and recovery probes."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
import tempfile
import time
import uuid

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPOSITORY / 'executor-service'))
sys.path.insert(0, str(REPOSITORY / 'acceptance/clients'))
from native_pool import NativePool, userns_service_args
from native_repository_fixture import sha, stop_unit


def call(*args, check=True, timeout=30):
    return subprocess.run(args, check=check, capture_output=True, text=True, timeout=timeout)


def http(socket, method, route, data=None):
    client = pwd.getpwnam('daemon')
    command = ['curl', '--disable', '--noproxy', '*', '--silent', '--show-error', '--max-time', '15', '--max-filesize', '8388608',
               '--unix-socket', str(socket), '--request', method, '--write-out', '\n%{http_code}',
               'http://localhost' + route]
    if data is not None:
        command += ['--header', 'Content-Type: application/json', '--data-binary', '@-']
    result = subprocess.run(command, input=json.dumps(data).encode() if data is not None else None,
                            user=client.pw_uid, group=client.pw_gid, extra_groups=[],
                            check=True, capture_output=True, timeout=20)
    body, status = result.stdout.rsplit(b'\n', 1)
    return int(status), json.loads(body)


class JavaNativeFixture:
    def __init__(self, binary, tools, report, *, volumes=False):
        assert os.geteuid() == 0
        self.report = report.resolve()
        with self.report.open('x', encoding='utf-8') as output:
            json.dump({'result': 'STARTING'}, output)
        self.root = Path(tempfile.mkdtemp(prefix='poketto-java-native-', dir='/var/lib'))
        self.root.chmod(0o755)
        self.client = pwd.getpwnam('daemon')
        token = uuid.uuid4().hex[:8]
        self.pool = NativePool(token, memory='384M', tasks=256, cpu=50)
        self.slice = self.pool.name
        self.name = 'poketto-java-' + token
        self.unit = self.name + '.service'
        self.group = Path('/sys/fs/cgroup') / self.slice / self.unit
        self.disk = self.root / 'disk'
        self.socket = self.disk / 'runtime/control.sock'
        self.workloads_group = self.group / ('sandcrab-' + hashlib.sha256(str(self.socket.parent).encode()).hexdigest()) / 'workloads'
        self.binary = self.root / 'sandcrab'
        for source, target in ((binary, self.binary), (binary.with_name('bwrap'), self.root / 'bwrap')):
            shutil.copyfile(source, target)
            target.chmod(0o755)
            assert sha(source) == sha(target)
        self.config = self.root / 'config.toml'
        self.mounted = False
        self.volume_mounted = False
        self.storage = self.root / 'storage'
        self.data = self.storage / 'data'
        self.image = self.root / 'fixture.xfs'
        self.volume_image = self.root / 'volumes.xfs'
        self.volumes = volumes
        self.config.write_text(self.configuration(tools))
        self.config.chmod(0o600)
        self.receipt = {'result': 'NOT_RUN', 'cleanup': 'NOT_RUN', 'checks': [],
                        'binarySha256': sha(self.binary), 'fixtureSha256': sha(Path(__file__)),
                        'bounds': {'sliceMemoryMiB': 384, 'sliceCpuPercent': 50, 'sliceTasks': 256,
                                   'xfsImagesMiB': [512, 512] if volumes else [512]}}

    def configuration(self, tools):
        service = f'''[service]
runtime_root = {json.dumps(str(self.socket.parent))}
socket = {json.dumps(str(self.socket))}
client_uid = {self.client.pw_uid}
client_gid = {self.client.pw_gid}
cgroup_root = {json.dumps(str(self.group))}
daemon_memory_bytes = 134217728
daemon_tasks_max = 128
max_sandboxes = 2
max_records = 8
max_output_bytes = 1048576
max_event_streams = 1
'''
        profiles = [('python', 'nobody'), ('other', 'bin')] if self.volumes else [('python', 'nobody')]
        for name, user in profiles:
            service += f'''
[profiles.{name}]
exec_user = {json.dumps(user)}
tools = {json.dumps(str(tools))}
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache"]
memory_bytes = 67108864
temporary_bytes = 8388608
temporary_inodes = 1024
tasks_max = 64
cpu_quota_percent = 50
timeout_millis = {10000 if self.volumes else 30000}
session_ttl_millis = 60000
output_limit_bytes = 4096
network = "none"
host_calls = true
'''
        if self.volumes:
            service += f'''
[storage]
root = {json.dumps(str(self.storage))}
pool_bytes = 536870912
max_volume_bytes = 8388608
max_volume_inodes = 256
max_volumes = 4
max_records = 12
require_quota = true
'''
        return service

    def setup(self):
        self.disk.mkdir(mode=0o755)
        self.disk.chmod(0o755)
        with self.image.open('xb') as image:
            image.truncate(512 * 1024 * 1024)
        call('mkfs.xfs', '-f', '-m', 'reflink=1', str(self.image))
        call('mount', '-o', 'loop,prjquota,nosuid,nodev', str(self.image), str(self.disk))
        self.mounted = True
        self.disk.chmod(0o755)
        if self.volumes:
            self.data.mkdir(parents=True, mode=0o755)
            self.storage.chmod(0o755)
            self.data.chmod(0o755)
            with self.volume_image.open('xb') as image:
                image.truncate(512 * 1024 * 1024)
            call('mkfs.xfs', '-f', '-m', 'reflink=1', str(self.volume_image))
            call('mount', '-o', 'loop,prjquota,nosuid,nodev', str(self.volume_image), str(self.data))
            self.volume_mounted = True
        self.pool.start()
        self.start()
        if self.volumes:
            assert self.info['storage']['quotaEnforced'] is True

    def start(self):
        deadline = time.monotonic() + 10
        while call('systemctl', 'show', self.unit, '-p', 'LoadState', '--value').stdout.strip() != 'not-found' or self.group.exists():
            assert time.monotonic() < deadline, 'Previous owned unit or cgroup was not collected'
            time.sleep(.05)
        call('systemd-run', '--quiet', '--collect', '--unit', self.unit, '--slice', self.slice,
             '-p', 'Delegate=yes', '-p', 'MemorySwapMax=0', '-p', 'TasksMax=infinity',
             '-p', 'Environment=PATH=' + getattr(self, 'launch_path', '/usr/bin:/bin'),
             '-p', 'KillMode=control-group', '-p', 'UMask=0077', *userns_service_args(),
             str(self.binary), 'serve', '--config', str(self.config))
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if self.socket.is_socket():
                try:
                    status, self.info = http(self.socket, 'GET', '/v1/info')
                    if status == 200:
                        return
                except (OSError, ValueError, subprocess.SubprocessError):
                    pass
            state = call('systemctl', 'show', self.unit, '-p', 'ActiveState', '--value').stdout.strip()
            assert state not in ('failed', 'inactive'), 'Native fixture stopped before readiness'
            time.sleep(.1)
        raise TimeoutError('Native fixture did not become ready')

    def restart(self):
        stop_unit(self.unit)
        self.start()

    def done(self, name):
        self.receipt['checks'].append({'name': name, 'result': 'PASS'})
        self.save()
        print(name + ': PASS', flush=True)

    def save(self):
        self.report.write_text(json.dumps(self.receipt, indent=2) + '\n')

    def cleanup(self):
        try:
            stop_unit(self.unit)
            if self.group.exists():
                assert 'populated 0' in (self.group / 'cgroup.events').read_text().splitlines()
            mounts = [line for line in call('findmnt', '-rn', '-o', 'TARGET').stdout.splitlines()
                      if line == str(self.root) or line.startswith(str(self.root) + '/')]
            expected = ([str(self.disk)] if self.mounted else []) + ([str(self.data)] if self.volume_mounted else [])
            assert sorted(mounts) == sorted(expected), 'Owned fixture still has runtime mounts'
            if self.volume_mounted:
                call('umount', str(self.data))
                self.volume_mounted = False
            if self.mounted:
                call('umount', str(self.disk))
                self.mounted = False
            self.pool.close()
            assert self.root.parent == Path('/var/lib') and self.root.name.startswith('poketto-java-native-')
            assert self.root.resolve() == self.root
            shutil.rmtree(self.root)
            self.receipt['cleanup'] = 'PASS'
        except BaseException:
            self.receipt.update(result='FAIL', cleanup='FAIL')
            if self.root.is_dir():
                self.root.chmod(0o700)
            raise
