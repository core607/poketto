"""Own the independent Sandcrab instance used by the HTTP/MCP acceptance controller."""

import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import time


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(65536):
            digest.update(block)
    return digest.hexdigest()


def stop_unit(unit):
    subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=40)
    observed = subprocess.run(['systemctl', 'show', unit, '-p', 'LoadState', '-p', 'ActiveState'],
                              capture_output=True, text=True, timeout=10)
    fields = dict(line.split('=', 1) for line in observed.stdout.splitlines() if '=' in line)
    assert fields.get('LoadState') in ('loaded', 'not-found')
    assert fields.get('ActiveState') in ('inactive', 'failed'), 'Owned fixture unit survived cleanup'
    if fields['LoadState'] == 'loaded' and fields['ActiveState'] == 'failed':
        subprocess.run(['systemctl', 'reset-failed', unit], check=True, capture_output=True, timeout=10)


def verify_runtime(runtime):
    expected = {}
    manifest = runtime / 'manifest.sha256'
    for line in manifest.read_text().splitlines():
        digest, relative = line.split('  ', 1)
        name = Path(relative)
        assert not name.is_absolute() and '..' not in name.parts and relative not in expected
        target = runtime / name
        assert target.resolve() == target and target.is_file() and sha(target) == digest
        expected[relative] = digest
    entries = list(runtime.rglob('*'))
    assert not any(p.is_symlink() for p in entries)
    assert expected.keys() == {p.relative_to(runtime).as_posix() for p in entries if p.is_file() and p != manifest}
    return sha(manifest)


def configuration(runtime, tools, account, execution_user, unit, resource_slice, agents):
    slots = max(4, 2 * agents + 2)
    return f'''[service]
runtime_root = {json.dumps(str(runtime))}
socket = {json.dumps(str(runtime / 'control.sock'))}
client_uid = {account.pw_uid}
client_gid = {account.pw_gid}
cgroup_root = "/sys/fs/cgroup/{resource_slice}/{unit}.service"
daemon_memory_bytes = 134217728
daemon_tasks_max = 128
max_sandboxes = {slots}
max_records = 1024
max_output_bytes = 16777216
max_templates = 64
template_pool_bytes = 134217728
max_file_records = 256
max_file_transfers = {min(16, slots)}
max_connections = {min(256, max(40, 4 * agents + 12))}
max_event_streams = {min(128, max(16, 2 * agents + 2))}

[profiles.repository]
exec_user = {json.dumps(execution_user)}
tools = {json.dumps(str(tools))}
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache"]
memory_bytes = 201326592
temporary_bytes = 33554432
temporary_inodes = 4096
tasks_max = 48
cpu_quota_percent = 50
timeout_millis = 60000
session_ttl_millis = 60000
output_limit_bytes = 1048576
transfer_bytes = 67108864
network = "none"
host_calls = true
'''


class RepositorySandbox:
    def __init__(self, root, disk, binary, tools, account, execution_user, resource_slice, token, agents):
        self.account = account
        self.unit = 'poketto-client-' + token + '-repository'
        self.resource_slice = resource_slice
        self.runtime = disk / 'sandcrab'
        self.socket = self.runtime / 'control.sock'
        self.programs = root / 'sandbox-programs'
        self.programs.mkdir(mode=0o755)
        self.programs.chmod(0o755)
        self.binary = self.programs / 'sandcrab'
        for src, name in [(binary, 'sandcrab'), (binary.with_name('bwrap'), 'bwrap')]:
            target = self.programs / name
            shutil.copyfile(src, target)
            target.chmod(0o755)
            assert sha(src) == sha(target)
        self.config = root / 'repository.toml'
        self.config.write_text(configuration(self.runtime, tools, account, execution_user, self.unit, resource_slice, agents))
        self.config.chmod(0o600)
        self.submitted = False

    def start(self):
        subprocess.run([self.binary, 'check', '--config', self.config], check=True, capture_output=True, timeout=15)
        options = ['Delegate=yes', 'MemorySwapMax=0', 'TasksMax=infinity', 'KillMode=control-group', 'UMask=0077']
        restricted = Path('/proc/sys/kernel/apparmor_restrict_unprivileged_userns')
        if restricted.exists() and restricted.read_text().strip() == '1':
            options.append('AppArmorProfile=sandcrab-service')
        self.submitted = True
        subprocess.run(['systemd-run', '--quiet', '--collect', '--unit', self.unit, '--slice', self.resource_slice,
                        *[part for value in options for part in ('-p', value)], self.binary, 'serve', '--config', self.config],
                       check=True, capture_output=True, timeout=30)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if self.socket.is_socket():
                check = subprocess.run([self.binary, 'info', '--socket', self.socket], capture_output=True,
                                       user=self.account.pw_uid, group=self.account.pw_gid, extra_groups=[], timeout=5)
                if check.returncode == 0:
                    info = json.loads(check.stdout)
                    assert {'sessions', 'templates', 'files', 'uploads', 'calls'} <= set(info['capabilities'])
                    assert info['profiles']['repository']['hostCalls'] and info['profiles']['repository']['network'] == 'none'
                    return info['serverId']
            time.sleep(.1)
        raise RuntimeError('The repository service did not open its authenticated socket')

    def close(self):
        if not self.submitted:
            return
        unit = self.unit + '.service'
        stop_unit(unit)
        group = Path('/sys/fs/cgroup') / self.resource_slice / unit
        if group.exists():
            assert 'populated 0' in (group / 'cgroup.events').read_text().splitlines()
