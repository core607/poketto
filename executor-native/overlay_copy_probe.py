"""Run Poketto's actual repository adapter across process loss on a disposable Linux host."""
import argparse
from contextlib import contextmanager
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tarfile
import tempfile
import time
import uuid


def run(*args, **kwargs):
    return subprocess.run([str(arg) for arg in args], check=True, capture_output=True, timeout=kwargs.pop('timeout', 30),
                          **kwargs).stdout


def digest(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def api(socket, args, method, route, body=None):
    command = ['curl', '--silent', '--show-error', '--max-time', '30', '--unix-socket', socket,
               '--request', method, '--write-out', '\n%{http_code}', 'http://localhost/v1/' + route]
    if body is not None:
        content_type = 'application/octet-stream' if isinstance(body, bytes) else 'application/json'
        command.extend(['--header', 'Content-Type: ' + content_type, '--data-binary', '@-'])
        if content_type == 'application/json':
            body = json.dumps(body).encode()
    response = run(*command, input=body, user=args.uid, group=args.gid, extra_groups=[])
    value, status = response.rsplit(b'\n', 1)
    return int(status), json.loads(value)


def control(socket, args, area, scope, action):
    marker = {'write': 'ACKNOWLEDGED ', 'read': 'RESTORED ', 'save': 'SAVED '}[action]
    target = args.output / f'{scope}-{action}.log'
    agent, = (args.runtime / 'jars').glob('byte-buddy-agent-*.jar')
    command = [str(args.java), '-Xmx192m', '-XX:MaxMetaspaceSize=160m', '-Duser.home=' + str(area),
               '-javaagent:' + str(agent), '-cp', str(args.runtime / 'classes') + ':' + str(args.runtime / 'jars/*'),
               'io.github.core607.poketto.executor.internal.NativeOverlayCopyProbe',
               str(socket), str(area), scope, action, 'repository']
    with target.open('xb') as log:
        process = subprocess.Popen(command, cwd=area, user=args.uid, group=args.gid, extra_groups=[], stdout=log,
                                   stderr=subprocess.STDOUT, env={'HOME': str(area), 'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})
        try:
            deadline = time.monotonic() + 150
            while time.monotonic() < deadline:
                if target.stat().st_size > 8 * 1024**2:
                    raise RuntimeError('Application diagnostics exceeded their bound')
                values = [line[len(marker):] for line in target.read_text(errors='replace').splitlines()
                          if line.startswith(marker)]
                if values:
                    if len(values) != 1:
                        raise RuntimeError('Application produced multiple acknowledgements')
                    if action == 'write':
                        process.kill()
                        assert process.wait(timeout=10) == -signal.SIGKILL
                    else:
                        assert process.wait(timeout=20) == 0
                    return json.loads(values[0])
                if process.poll() is not None:
                    raise RuntimeError(f'{scope} {action} exited {process.returncode} before its acknowledgement')
                time.sleep(.1)
            raise TimeoutError(f'{scope} {action} did not acknowledge before its deadline')
        finally:
            if process.poll() is None:
                process.kill()
                process.wait(timeout=10)


def exercise(socket, args, area, receipt):
    def check(name):
        receipt['checks'].append(name)
        print(name, flush=True)

    sentinel = io.BytesIO()
    with tarfile.open(fileobj=sentinel, mode='w') as archive:
        item = tarfile.TarInfo('unrelated')
        item.size = 4
        archive.addfile(item, io.BytesIO(b'keep'))
    status, foreign = api(socket, args, 'POST', 'templates?profile=repository', sentinel.getvalue())
    assert status == 200
    copies = {}
    for scope in ('full', 'other'):
        copies[scope] = control(socket, args, area, scope, 'write')
        assert copies[scope]['upper']['bytes'] > 1024
    assert copies['full']['copy'] != copies['other']['copy'] and copies['full']['template'] == copies['other']['template']
    assert len(list((area / 'metadata').glob('*.account'))) == 2
    check('two-killed-application-processes-left-independent-uppers-on-one-template')
    for scope in ('full', 'other'):
        assert control(socket, args, area, scope, 'read')['copy'] == copies[scope]['copy']
        assert api(socket, args, 'GET', 'templates/' + copies[scope]['template'])[0] == (200 if scope == 'full' else 404)
    check('both-authorized-reconnections-restored-content-links-mode-home-and-whiteouts-before-last-pin-retirement')
    copies['public'] = control(socket, args, area, 'public', 'write')
    assert copies['public']['template'] != copies['full']['template']
    assert control(socket, args, area, 'public', 'read')['copy'] == copies['public']['copy']
    assert api(socket, args, 'GET', 'templates/' + copies['public']['template'])[0] == 404
    check('public-projection-survived-process-loss-and-disposal')
    assert control(socket, args, area, 'full', 'save')['pushes'] == 1
    check('guest-cli-and-host-verified-selected-bytes-before-one-real-git-push')
    assert not list((area / 'metadata').glob('*.upper-*')) and not list((area / 'metadata').glob('*.account'))
    for collection in ('runs', 'sessions'):
        assert api(socket, args, 'GET', collection)[1]['ids'] == []
    assert [entry['id'] for entry in api(socket, args, 'GET', 'templates')[1]['templates']] == [foreign['id']]
    assert api(socket, args, 'DELETE', 'templates/' + foreign['id']) == (200, {'deleted': True})
    check('journal-and-native-inventories-empty-after-preserving-the-unrelated-template')
    receipt['upperBytes'] = {key: copy['upper']['bytes'] for key, copy in copies.items()}


def argument_parser(description):
    parser = argparse.ArgumentParser(description=description)
    for name in ('binaries', 'runtime', 'java', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--uid', type=int, required=True)
    parser.add_argument('--gid', type=int, required=True)
    return parser


def mount_pool(image, mount):
    with image.open('xb') as out:
        out.truncate(512 * 1024**2)
    run('mkfs.xfs', '-f', '-m', 'reflink=1', image)
    run('mount', '-o', 'loop,prjquota,nosuid,nodev', image, mount)


@contextmanager
def native_service(args, receipt, volumes=False):
    if os.geteuid() != 0 or os.environ.get('SANDCRAB_INSTALL_DISPOSABLE') != '1' or min(args.uid, args.gid) <= 0:
        raise ValueError('Use a disposable root systemd host and a distinct unprivileged application account')
    args.runtime = args.runtime.resolve(strict=True)
    args.java = args.java.resolve(strict=True)
    args.output = args.output.absolute()
    args.output.mkdir(mode=0o755)
    root = Path(tempfile.mkdtemp(prefix='repo-ov-', dir='/var/lib'))
    unit = 'repocopy-' + uuid.uuid4().hex[:12] + '.service'
    cgroup = Path('/sys/fs/cgroup/system.slice') / unit
    mount = root / 'data'
    started = False
    mounted = []
    storage = root / 'storage'

    def interrupted(signum, frame):
        raise InterruptedError('Repository acceptance interrupted')

    signal.signal(signal.SIGTERM, interrupted)
    try:
        root.chmod(0o755)
        for folder in (root / 'bin', root / 'tools', mount):
            folder.mkdir(mode=0o755)
        for name in ('sandcrab', 'bwrap'):
            shutil.copyfile(args.binaries / name, root / 'bin' / name)
            (root / 'bin' / name).chmod(0o755)
        run('sha256sum', '--check', '--quiet', 'manifest.sha256', cwd=args.runtime, user=args.uid, group=args.gid, extra_groups=[])
        receipt['runtimeManifestSha256'] = digest(args.runtime / 'manifest.sha256')
        mount_pool(root / 'fixture.xfs', mount)
        mounted.append(mount)
        mount.chmod(0o755)
        area = mount / 'application'
        area.mkdir(mode=0o700)
        os.chown(area, args.uid, args.gid)
        socket = mount / 'runtime/control.sock'
        cfg = root / 'config.toml'
        cfg.write_text(f'''[service]
runtime_root = "{socket.parent}"
socket = "{socket}"
client_uid = {args.uid}
client_gid = {args.gid}
cgroup_root = "{cgroup}"
daemon_memory_bytes = 134217728
daemon_tasks_max = 128
max_sandboxes = 4
max_records = 64
max_output_bytes = 4194304
max_templates = 8
template_pool_bytes = 33554432
[profiles.repository]
exec_user = "nobody"
tools = "{root / 'tools'}"
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache"]
memory_bytes = 100663296
temporary_bytes = 16777216
temporary_inodes = 2048
tasks_max = 64
cpu_quota_percent = 75
timeout_millis = 60000
session_ttl_millis = 120000
output_limit_bytes = 65536
transfer_bytes = 4194304
host_calls = true
network = "none"
''')
        if volumes:
            data = storage / 'data'
            data.mkdir(parents=True)
            mount_pool(root / 'volumes.xfs', data)
            mounted.append(data)
            with cfg.open('a') as config:
                config.write(f'''
[storage]
root = "{storage}"
require_quota = true
pool_bytes = 536870912
max_volume_bytes = 8388608
max_volume_inodes = 256
max_volumes = 4
max_records = 12
''')
        cfg.chmod(0o600)
        started = True
        run('systemd-run', '--unit', unit, '--collect', '--quiet', '--slice=system.slice',
            '-p', 'Delegate=yes', '-p', 'MemoryMax=512M', '-p', 'MemorySwapMax=0', '-p', 'CPUQuota=100%',
            '-p', 'TasksMax=256', '-p', 'KillMode=control-group', root / 'bin/sandcrab', 'serve', '--config', cfg)
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            try:
                if api(socket, args, 'GET', 'info')[0] == 200:
                    break
            except subprocess.CalledProcessError:
                time.sleep(.1)
        else:
            raise TimeoutError('Native service did not open its authenticated socket')
        pid = int(run('systemctl', 'show', unit, '-p', 'MainPID', '--value'))
        receipt['runningSandcrabSha256'] = digest(Path(f'/proc/{pid}/exe'))
        assert receipt['runningSandcrabSha256'] == digest(root / 'bin/sandcrab')
        yield socket, area, storage
        receipt['result'] = 'PASS'
    except Exception as failure:
        receipt['error'] = str(failure)
        if started:
            journal = subprocess.run(['journalctl', '-u', unit, '--no-pager', '-n', '50'], capture_output=True, timeout=15)
            (args.output / 'service.log').write_bytes(journal.stdout)
        raise
    finally:
        try:
            if started:
                subprocess.run(['systemctl', 'stop', unit], timeout=30, check=False)
                assert not cgroup.exists() or 'populated 0' in (cgroup / 'cgroup.events').read_text().splitlines()
            for disk in reversed(mounted):
                run('umount', disk)
            assert not any(line.split()[4].startswith(str(root) + '/') for line in Path('/proc/self/mountinfo').read_text().splitlines())
            assert root.parent == Path('/var/lib') and root.name.startswith('repo-ov-') and root.resolve() == root
            shutil.rmtree(root)
            receipt['cleanup'] = 'PASS'
        except BaseException:
            receipt['result'] = 'FAIL'
            receipt['cleanup'] = 'UNCONFIRMED'
            raise
        finally:
            (args.output / 'summary.json').write_text(json.dumps(receipt, indent=2) + '\n')


def main():
    args = argument_parser(__doc__).parse_args()
    receipt = {'result': 'FAIL', 'cleanup': 'NOT_STARTED', 'checks': [],
               'scope': 'Real repository adapter, Sandcrab Unix API and XFS journal with synthetic Git data and grants.'}
    with native_service(args, receipt) as (socket, area, _):
        exercise(socket, args, area, receipt)


if __name__ == '__main__':
    main()
