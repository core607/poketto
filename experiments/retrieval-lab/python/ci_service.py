"""Provision one bounded native service for the retrieval consumer on a disposable CI runner."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import uuid


def run(*args, **kwargs):
    return subprocess.run(list(map(str, args)), check=True, text=True, capture_output=True,
                          timeout=kwargs.pop('timeout', 60), **kwargs)


def stop(unit):
    cgroup = Path('/sys/fs/cgroup/system.slice') / unit
    stopped = subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=90)
    if cgroup.exists():
        assert stopped.returncode == 0 and 'populated 0' in (cgroup / 'cgroup.events').read_text().splitlines()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('binaries', 'node', 'data', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    for name in ('uid', 'gid'):
        parser.add_argument('--' + name, type=int, required=True)
    args = parser.parse_args()
    if os.geteuid() != 0 or min(args.uid, args.gid) <= 0 or os.environ.get('RUNNER_ENVIRONMENT') != 'github-hosted':
        parser.error('Run as root on a disposable hosted runner with an unprivileged client UID and GID')
    output = args.output.absolute()
    output.mkdir(mode=0o755)
    available = {path: shutil.disk_usage(path).free for path in (Path('/var/lib'), Path('/mnt'))
                 if path.is_dir() and path.resolve() == path and path.stat().st_uid == 0 and not path.stat().st_mode & 0o022}
    receipt = {'result': 'FAIL', 'cleanup': 'NOT_STARTED', 'freeBytes': {str(p): size for p, size in available.items()}}
    parent = next((p for p, size in available.items() if size >= 12 * 1024**3), None)
    if parent is None:
        (output / 'host.json').write_text(json.dumps(receipt, indent=2) + '\n')
        parser.error('The complete-corpus fixture needs 12 GiB of free disk on a protected host filesystem')
    root = Path(tempfile.mkdtemp(prefix='labcorpus-', dir=parent))
    unit = 'labcorpus-' + uuid.uuid4().hex[:12] + '.service'
    controller = 'labclient-' + uuid.uuid4().hex[:12] + '.service'
    cgroup = Path('/sys/fs/cgroup/system.slice') / unit
    store = root / 'storage'
    target = store / 'data'
    socket = root / 'runtime/control.sock'
    started = False
    controller_started = False
    receipt.update(cleanup='UNCONFIRMED', fixtureFilesystem=str(parent))
    def interrupted(signum, frame):
        raise InterruptedError('Corpus acceptance interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    try:
        root.chmod(0o755)
        for path in (root / 'bin', root / 'tools', store, target):
            path.mkdir(mode=0o755)
            path.chmod(0o755)
        for name in ('sandcrab', 'bwrap'):
            shutil.copyfile(args.binaries / name, root / 'bin' / name)
            (root / 'bin' / name).chmod(0o755)
        exe = root / 'bin/sandcrab'
        image = root / 'quota.xfs'
        with image.open('xb') as f:
            f.truncate(8 * 1024**3)
        run('mkfs.xfs', '-f', '-m', 'reflink=1', image)
        run('mount', '-o', 'loop,prjquota,nosuid,nodev', image, target)
        config = root / 'config.toml'
        config.write_text(f'''[service]
runtime_root = "{socket.parent}"
socket = "{socket}"
client_uid = {args.uid}
client_gid = {args.gid}
cgroup_root = "{cgroup}"
daemon_memory_bytes = 268435456
daemon_tasks_max = 128
max_sandboxes = 2
max_records = 64
max_output_bytes = 16777216
[storage]
root = "{store}"
pool_bytes = 8589934592
max_volume_bytes = 2147483648
max_volume_inodes = 150000
max_volumes = 4
max_records = 12
require_quota = true
[profiles.corpus]
exec_user = "nobody"
tools = "{root / 'tools'}"
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache"]
memory_bytes = 536870912
temporary_bytes = 33554432
temporary_inodes = 4096
tasks_max = 64
cpu_quota_percent = 100
timeout_millis = 120000
session_ttl_millis = 60000
output_limit_bytes = 1048576
transfer_bytes = 268435456
network = "none"
host_calls = false
''')
        config.chmod(0o600)
        restriction = Path('/proc/sys/kernel/apparmor_restrict_unprivileged_userns')
        userns = ['-p', 'AppArmorProfile=sandcrab-service'] if restriction.exists() and restriction.read_text().strip() == '1' else []
        run('systemd-run', '--unit', unit, '--slice=system.slice', '--collect', '--quiet',
            '-p', 'Delegate=yes', '-p', 'MemoryMax=1G', '-p', 'MemorySwapMax=0',
            '-p', 'CPUQuota=100%', '-p', 'TasksMax=256', '-p', 'KillMode=control-group',
            *userns, exe, 'serve', '--config', config)
        started = True
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            try:
                info = json.loads(run(exe, 'info', '--socket', socket, user=args.uid, group=args.gid,
                                      extra_groups=[], timeout=3).stdout)
                assert info['storage']['quotaEnforced'] is True
                break
            except subprocess.CalledProcessError:
                time.sleep(.1)
        else:
            raise TimeoutError('Native corpus service did not become ready')
        pid = int(run('systemctl', 'show', unit, '-p', 'MainPID', '--value').stdout)
        with Path(f'/proc/{pid}/exe').open('rb') as f:
            receipt['runningBinarySha256'] = hashlib.file_digest(f, 'sha256').hexdigest()
        client = output / 'client'
        client.mkdir(mode=0o700)
        os.chown(client, args.uid, args.gid)
        controller_started = True
        result = run('systemd-run', '--unit', controller, '--slice=system.slice', '--wait', '--pipe', '--collect', '--quiet',
                     '-p', f'User={args.uid}', '-p', f'Group={args.gid}', '-p', 'MemoryMax=512M',
                     '-p', 'MemorySwapMax=0', '-p', 'TasksMax=128', '-p', 'CPUQuota=100%',
                     '/usr/bin/python3', Path(__file__).with_name('native_fixture.py').resolve(),
                     '--binary', exe, '--socket', socket, '--node', args.node.resolve(strict=True),
                     '--data', args.data.resolve(strict=True), '--output', client / 'attempt', timeout=810)
        (output / 'controller.stdout').write_text(result.stdout)
        summary = json.loads((client / 'attempt/summary.json').read_bytes())
        assert summary['suppliedBinarySha256'] == receipt['runningBinarySha256'] and summary['cleanup'] == 'PASS'
        receipt['result'] = 'PASS'
    except BaseException as error:
        receipt['failure'] = str(error)
        if isinstance(error, subprocess.CalledProcessError):
            (output / 'controller.stderr').write_text(error.stderr or '')
        raise
    finally:
        try:
            if controller_started:
                stop(controller)
            if started:
                journal = run('journalctl', '-u', unit, '--no-pager', '-n', '80')
                (output / 'service.log').write_text(journal.stdout)
                stop(unit)
            if target.is_mount():
                run('umount', target)
            mounts = run('findmnt', '-rn', '-o', 'TARGET').stdout.splitlines()
            assert all(not Path(mount).is_relative_to(root) for mount in mounts)
            assert root.resolve() == root and root.parent == parent and root.name.startswith('labcorpus-')
            shutil.rmtree(root)
            receipt['cleanup'] = 'PASS'
        finally:
            (output / 'host.json').write_text(json.dumps(receipt, indent=2) + '\n')


if __name__ == '__main__':
    main()
