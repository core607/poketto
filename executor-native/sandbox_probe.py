#!/usr/bin/env python3
"""Serial, bounded launcher comparison on a real host; uses no application copies or credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import statistics
import subprocess
import sys
import tempfile
import time
import uuid


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=20, **kwargs).stdout.strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def launch_command(launcher):
    """The previous Python launcher runs under the system interpreter; the Rust launcher runs directly."""
    return ['/usr/bin/python3', '-I', str(launcher)] if launcher.suffix == '.py' else [str(launcher)]


def integrity_checks(root, tools, launcher, record, user, verify_tools):
    copied = root / 'integrity-tools'
    for relative in ('extracted/usr/bin/bwrap',
                     'node_modules/@anthropic-ai/sandbox-runtime/vendor/seccomp/x64/apply-seccomp'):
        target = copied / relative
        target.parent.mkdir(parents=True, mode=0o755, exist_ok=True)
        shutil.copy2(tools / relative, target)
    verify_tools(copied)
    invalid_record = root / 'invalid-tools.json'
    invalid_record.write_text(json.dumps({**json.loads(record.read_text()), 'tools': str(copied)}))
    invalid_record.chmod(0o444)
    for name, relative, expected in (
            ('seccomp-tamper', 'node_modules/@anthropic-ai/sandbox-runtime/vendor/seccomp/x64/apply-seccomp', 'checksum mismatch'),
            ('writable-bwrap', 'extracted/usr/bin/bwrap', 'root-owned and not writable')):
        target = copied / relative
        original = target.read_bytes()
        if name == 'seccomp-tamper':
            target.write_bytes(original + b'modified')
        else:
            target.chmod(0o777)
        try:
            try:
                verify_tools(copied)
                raise AssertionError('Supervisor tool validation accepted ' + name)
            except ValueError as error:
                assert expected in str(error)
            attempt = subprocess.run(['runuser', '-u', user, '--', *launch_command(launcher), str(invalid_record)],
                                     capture_output=True, text=True, timeout=10)
            assert attempt.returncode != 0 and expected in attempt.stderr, attempt.stderr
            print(json.dumps({'integrity': name, 'supervisorAndLauncher': 'PASS'}), flush=True)
        finally:
            target.write_bytes(original)
            target.chmod(0o755)


def compare_snapshots(reference, direct, driver_comparison=False, reference_driver='python3'):
    assert reference['assertions'] == direct['assertions']
    assert reference['security'] == direct['security']
    processes = []
    for snapshot, driver in ((reference, reference_driver), (direct, 'poketto-shell')):
        rows = [dict(row) for row in snapshot['processes']]
        selected = next(row for row in rows if row['pid'] == 2)
        assert selected['comm'] == driver
        selected['comm'] = '$DRIVER'
        processes.append(rows)
    assert processes[0] == processes[1]
    before, after = set(reference['environmentNames']), set(direct['environmentNames'])
    assert not after - before
    for name in before - after:
        assert (name.lower().endswith('_proxy') or name.startswith(('CLAUDE_CODE_HOST_', 'CLOUDSDK_PROXY_'))
                or name in ('GIT_CONFIG_PARAMETERS', 'GIT_SSH_COMMAND', 'JAVA_TOOL_OPTIONS')), name
    assert reference['temporaryDirectory'] == ('/tmp' if driver_comparison else '/tmp/claude')
    assert direct['temporaryDirectory'] == '/tmp'
    # The direct mount table contains only the grants, synthetic devices and fresh proc.
    for line in direct['mounts']:
        destination = line.split()[4]
        assert (destination in ('/', '/usr', '/etc/ld.so.cache', '/tmp', '/proc', '$TOOLS',
                                '$PROGRAMS', '$REFERENCE_PROGRAMS')
                or destination == '/dev' or destination.startswith('/dev/')
                or destination.startswith('$FIXTURE/sessions/')), line


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worker-source', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    reference = parser.add_mutually_exclusive_group()
    reference.add_argument('--reference-launcher', type=Path)
    reference.add_argument('--reference-worker-source', type=Path,
                           help='Previous Python-driver source tree, including its launcher and shared CLI')
    parser.add_argument('--reference-driver', type=Path,
                        help='Python shell driver required by a standalone SRT reference launcher')
    parser.add_argument('--idle-seconds', type=int, default=0, choices=(0, 60))
    parser.add_argument('--toolkit', action='store_true')
    parser.add_argument('--iterations', type=int, default=3, choices=range(1, 10))
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.reference_launcher and not args.reference_driver:
        parser.error('--reference-launcher requires its --reference-driver')
    if args.idle_seconds and not args.reference_worker_source:
        parser.error('--idle-seconds requires --reference-worker-source')
    assert os.geteuid() == 0
    source, tools = args.worker_source.resolve(strict=True), args.tools.resolve(strict=True)
    sys.path.insert(0, str(source))
    from command_channel import CommandChannel, CommandEnded
    from native_pool import NativePool
    from worker import Session, SystemdBackend, verify_launch
    launcher = source / 'poketto-launch'

    def verify_tools(path):
        verify_launch(launcher, path)

    verify_tools(tools)
    token = uuid.uuid4().hex[:8]
    root = Path(tempfile.mkdtemp(prefix='poketto-sandbox-' + token + '-', dir='/var/lib'))
    root.chmod(0o755)
    user = 'pkt-sbox-' + token
    pool = NativePool(token, memory='384M', tasks=128)
    created_user = False
    units, measurements, snapshots = [], [], {}
    launchers = [('direct', launcher)]
    reference_source = args.reference_worker_source.resolve(strict=True) if args.reference_worker_source else None
    reference_programs = reference_source / 'bin' if reference_source else None
    if args.reference_launcher:
        launchers.insert(0, ('reference', args.reference_launcher.resolve(strict=True)))
    elif reference_source:
        launchers.insert(0, ('reference', reference_source / 'launcher.py'))
    try:
        pool.start()
        run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
        created_user = True
        account = pwd.getpwnam(user)
        backend = object.__new__(SystemdBackend)
        backend.c = {'toolsRoot': str(tools), 'launcher': str(launcher)}
        backend.sessions = root / 'sessions'
        backend.sessions.mkdir(mode=0o755)
        session = Session(str(uuid.uuid4()), (), '', time.time() + 600)
        lease = backend.mount_path(session)
        lease.mkdir(mode=0o755)
        for name in ('work', 'work/repository', 'home', 'bridge', 'bridge/requests'):
            p = lease / name
            p.mkdir(mode=0o755)
            os.chown(p, account.pw_uid, account.pw_gid)
        for name in ('bridge/lock', 'bridge/state', 'bridge/responses'):
            (lease / name).write_text('')
            (lease / name).chmod(0o444)
        bootstrap = lease / 'bootstrap'
        bootstrap.mkdir(mode=0o755)
        # A reference tree with the native driver compares only the launchers.
        reference_driver = reference_source / 'shell_loop.py' if reference_source else args.reference_driver
        if reference_driver and not reference_driver.exists():
            reference_driver = None
        if reference_driver:
            shutil.copyfile(reference_driver, bootstrap / 'shell_loop.py')
        shutil.copyfile(Path(__file__).with_name('sandbox_snapshot.py'), bootstrap / 'sandbox_snapshot.py')
        if args.toolkit:
            shutil.copyfile(Path(__file__).with_name('sandbox_toolkit.py'), bootstrap / 'sandbox_toolkit.py')
        # Reference SRT needs inert mandatory-deny targets in its root-owned working directory.
        for name in ('.gitconfig', '.gitmodules', '.bashrc', '.bash_profile', '.zshrc',
                     '.zprofile', '.profile', '.ripgreprc', '.mcp.json'):
            (bootstrap / name).touch(mode=0o444)
        for name in ('.vscode', '.idea', '.claude', '.claude/commands', '.claude/agents'):
            (bootstrap / name).mkdir(mode=0o555)
        bootstrap.chmod(0o555)
        canary = root / 'host-canary'
        canary.write_text('host-only fixture')
        canary.chmod(0o444)
        policy = backend.sandbox_policy(session, {'mode': 'shell'})
        if reference_programs:
            policy['filesystem']['allowRead'].append(str(reference_programs))
        settings = root / 'sandbox.json'
        settings.write_text(json.dumps(policy))
        settings.chmod(0o444)
        record = root / 'record.json'
        record.write_text(json.dumps({'mode': 'shell', 'root': str(lease),
                                     'tools': str(tools), 'settings': str(settings)}))
        record.chmod(0o444)
        integrity_checks(root, tools, launcher, record, user, verify_tools)
        # Both launchers get identical command bytes, grants, quotas and filesystem contents.
        for iteration in range(args.iterations):
            for label, program in launchers:
                available = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                                     if line.startswith('MemAvailable:')))
                assert available >= 1536 * 1024, 'Host memory guard stopped sandbox acceptance'
                unit = 'poketto-sbox-' + token + '-' + label + '-' + str(iteration) + '.service'
                units.append(unit)
                command = ['systemd-run', '--quiet', '--wait', '--pipe', '--unit', unit,
                           '--slice', pool.name, '-p', 'User=' + user, '-p', 'CPUQuota=50%',
                           '-p', 'MemoryMax=201326592', '-p', 'MemorySwapMax=0', '-p', 'TasksMax=64',
                           '-p', 'KillMode=control-group', '-p', 'TimeoutStopSec=1', '-p', 'SendSIGKILL=yes',
                           '-p', 'RuntimeMaxSec=' + str(60 + args.idle_seconds),
                           '-p', 'NoNewPrivileges=yes', '-p', 'UMask=0077',
                           '-p', 'TemporaryFileSystem=/tmp:rw,size=8388608,nr_inodes=1024,mode=1777,nosuid,nodev',
                           '-p', 'BindReadOnlyPaths=' + str(tools) + ' ' + str(program) + ' ' + str(record) + ' ' + str(settings),
                           '-p', 'BindPaths=' + str(lease),
                           '/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', *launch_command(program), str(record)]
                process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                channel = CommandChannel(process)
                timings = {}
                try:
                    value, output, truncated = channel.execute(str(uuid.uuid4()), 'true', 30000,
                                                               lambda: False, lambda: 'cancelled', timings, lambda: None)
                    assert value['exitCode'] == 0 and not any(truncated), output
                    group = run(['systemctl', 'show', unit, '--property=ControlGroup', '--value'])
                    cgroup = Path('/sys/fs/cgroup') / group.lstrip('/')
                    cpu = dict(line.split() for line in (cgroup / 'cpu.stat').read_text().splitlines())
                    sample = {'path': label, 'iteration': iteration, 'startupCpuUsec': int(cpu['usage_usec']),
                              'memoryBytes': int((cgroup / 'memory.current').read_text()),
                              'idleTasks': int((cgroup / 'pids.current').read_text()), **timings}
                    measurements.append(sample)
                    print(json.dumps({'measurement': sample}), flush=True)
                    if args.idle_seconds and iteration == 0:
                        before_idle = int(dict(line.split() for line in (cgroup / 'cpu.stat').read_text().splitlines())['usage_usec'])
                        started_idle = time.monotonic()
                        print(json.dumps({'idleObservation': label, 'seconds': args.idle_seconds}), flush=True)
                        while time.monotonic() - started_idle < args.idle_seconds:
                            time.sleep(1)
                            free = int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines()
                                            if line.startswith('MemAvailable:')))
                            assert free >= 1536 * 1024, 'Host memory guard stopped idle observation'
                        sample['idleSeconds'] = time.monotonic() - started_idle
                        after_idle = int(dict(line.split() for line in (cgroup / 'cpu.stat').read_text().splitlines())['usage_usec'])
                        sample['idleCpuUsec'] = after_idle - before_idle
                        sample['idleMemoryBytes'] = int((cgroup / 'memory.current').read_text())
                        print(json.dumps({'idleMeasurement': sample}), flush=True)
                    if iteration == 0:
                        probe = f'python3 -I {bootstrap}/sandbox_snapshot.py {lease} {tools} {canary}'
                        value, output, truncated = channel.execute(str(uuid.uuid4()), probe, 10000,
                                                                   lambda: False, lambda: 'cancelled', {}, lambda: None)
                        assert value['exitCode'] == 0 and not any(truncated), bytes(output[1])
                        snapshot = json.loads(output[0])
                        normalized = json.dumps(snapshot).replace(str(root), '$FIXTURE').replace(str(tools), '$TOOLS')
                        if reference_programs:
                            normalized = normalized.replace(str(reference_programs), '$REFERENCE_PROGRAMS')
                        normalized = normalized.replace(str(source / 'bin'), '$PROGRAMS')
                        snapshots[label] = json.loads(normalized)
                        print(json.dumps({'snapshot': label, 'passedAssertions': len(snapshot['assertions'])}), flush=True)
                        if args.toolkit:
                            value, output, truncated = channel.execute(
                                str(uuid.uuid4()), f'python3 -I {bootstrap}/sandbox_toolkit.py', 30000,
                                lambda: False, lambda: 'cancelled', {}, lambda: None)
                            assert value['exitCode'] == 0 and not any(truncated), bytes(output[1])
                            snapshots[label]['toolkit'] = json.loads(output[0])
                            print(json.dumps({'toolkit': label, 'result': snapshots[label]['toolkit']}), flush=True)
                except CommandEnded as error:
                    raise RuntimeError(bytes(error.output[1]).decode(errors='replace')) from error
                finally:
                    stopped = subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=15)
                    assert stopped.returncode in (0, 5)
                    channel.close()
                    process.wait(timeout=10)
                    remaining = run(['systemctl', 'show', unit, '--property=ControlGroup', '--value'])
                    if remaining:
                        group = Path('/sys/fs/cgroup') / remaining.lstrip('/')
                        assert not group.exists() or (group / 'cgroup.events').read_text().splitlines()[0] == 'populated 0'
                    subprocess.run(['systemctl', 'reset-failed', unit], capture_output=True, timeout=5)
        medians = {label: {key: statistics.median(s[key] for s in measurements if s['path'] == label)
                           for key in ('startupCpuUsec', 'memoryBytes', 'idleTasks', 'readyMs')} for label, _ in launchers}
        result = {'measurements': measurements, 'medians': medians, 'snapshots': snapshots,
                  'sourceHashes': {label: digest(path) for label, path in launchers},
                  'programHashes': {name: digest(source / 'bin' / name) for name in ('poketto', 'poketto-shell', 'poketto-syncfs')},
                  'referenceDriverSha256': digest(reference_driver) if reference_driver else None,
                  'probeSha256': digest(Path(__file__)), 'snapshotSha256': digest(Path(__file__).with_name('sandbox_snapshot.py')),
                  'scope': 'Serial fresh shell true and idle cgroup charges; 50 percent CPU quota; not HTTP capacity.'}
        if 'reference' in medians:
            compare_snapshots(snapshots['reference'], snapshots['direct'], bool(reference_source),
                              'python3' if reference_driver else 'poketto-shell')
            result['ratios'] = {key: medians['direct'][key] / medians['reference'][key]
                                for key in ('startupCpuUsec', 'memoryBytes', 'idleTasks')}
        if args.idle_seconds:
            reference_idle = next(row for row in measurements if row['path'] == 'reference' and row['iteration'] == 0)
            direct_idle = next(row for row in measurements if row['path'] == 'direct' and row['iteration'] == 0)
            assert reference_idle['idleCpuUsec'] > 0
            result['idleCpuRatio'] = (direct_idle['idleCpuUsec'] / direct_idle['idleSeconds']) / (
                reference_idle['idleCpuUsec'] / reference_idle['idleSeconds'])
        args.output.write_text(json.dumps(result, indent=2) + '\n')
        if not reference_source:
            assert all(ratio <= 1 / 3 for ratio in result.get('ratios', {}).values()), result.get('ratios')
        if args.idle_seconds:
            assert result['idleCpuRatio'] <= 0.1, result['idleCpuRatio']
        print(json.dumps({'sandboxComparison': 'PASS', 'medians': medians,
                          'ratios': result.get('ratios')}), flush=True)
    finally:
        for unit in units:
            subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=15)
            subprocess.run(['systemctl', 'reset-failed', unit], capture_output=True, timeout=5)
        pool.close()
        if created_user:
            uid = pwd.getpwnam(user).pw_uid
            assert subprocess.run(['pgrep', '-u', str(uid)], capture_output=True).returncode == 1
            run(['userdel', user])
        assert root.parent == Path('/var/lib') and root.name.startswith('poketto-sandbox-' + token + '-')
        shutil.rmtree(root)
        print(json.dumps({'cleanup': 'PASS'}), flush=True)


if __name__ == '__main__':
    main()
