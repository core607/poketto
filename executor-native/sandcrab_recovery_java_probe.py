#!/usr/bin/env python3
"""Actual daemon faults while an ordinary Java observer is connected to an owned fixture."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import time
import uuid

from sandcrab_volume_java_probe import sha256, verify_runtime
import native_probe
from native_probe import Probe, call, http

ACTIONS = ('restart-output', 'restart-calls', 'restart-handler', 'enable-cleanup-fault', 'recover-cleanup')
MAIN = 'io.github.core607.poketto.executor.internal.SandcrabRecoveryNativeProbe'


def ordinary(path, limit):
    assert path.is_file() and not path.is_symlink() and path.stat().st_size <= limit
    return path.read_bytes()


def request(path, sequence, action, epoch):
    value = json.loads(ordinary(path, 4096))
    assert set(value) == {'sequence', 'action', 'run'}
    assert type(value['sequence']) is int and value['sequence'] == sequence and value['action'] == action
    assert set(value['run']) == {'id', 'serverId'}
    for identifier in value['run'].values():
        assert isinstance(identifier, str) and str(uuid.UUID(identifier)) == identifier
    assert value['run']['serverId'] == epoch
    return value


def acknowledge(directory, sequence, value):
    pending = directory / f'ack-{sequence}.pending'
    with pending.open('x', encoding='utf-8') as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    pending.chmod(0o644)
    pending.replace(directory / f'ack-{sequence}.json')


def fault_helper(probe):
    directory = probe.root / 'cleanup-fault'
    directory.mkdir(mode=0o755)
    flag = directory / 'fail-id'
    helper = directory / 'umount'
    helper.write_text(f'''#!/usr/bin/python3
import os, sys
from pathlib import Path
flag = Path({str(flag)!r})
if flag.exists() and Path(sys.argv[-1]).name == flag.read_text():
    raise SystemExit(79)
os.execv('/usr/bin/umount', ['umount', *sys.argv[1:]])
''')
    helper.chmod(0o755)
    probe.launch_path = str(directory) + ':/usr/bin:/bin'
    return flag


def old_unit(probe, run):
    unit = probe.name + '-' + run['id'] + '.service'
    assert unit.startswith(probe.name + '-')
    group = call('systemctl', 'show', unit, '-p', 'ControlGroup', '--value', check=False).stdout.strip()
    if group:
        assert group.startswith('/') and '..' not in Path(group).parts
        assert probe.slice in Path(group).parts
    return unit, group


def crash_and_restart(probe, run, unit, group):
    old_epoch = probe.info['serverId']
    old_pid = int(call('systemctl', 'show', probe.unit, '-p', 'MainPID', '--value').stdout.strip())
    assert old_pid > 0
    call('systemctl', 'kill', '--kill-whom=main', '--signal=KILL', probe.unit)
    call('systemctl', 'stop', probe.unit, check=False)
    call('systemctl', 'reset-failed', probe.unit, check=False)
    probe.start()
    assert probe.info['serverId'] != old_epoch
    assert call('systemctl', 'show', unit, '-p', 'ActiveState', '--value', check=False).stdout.strip() in ('', 'inactive', 'failed')
    if group:
        root = Path('/sys/fs/cgroup') / group.lstrip('/')
        assert not any(path.read_text().strip() for path in root.rglob('cgroup.procs'))
    assert not list((probe.socket.parent / 'jobs').iterdir()), 'Replacement daemon retained old fixture mounts'
    assert http(probe.socket, 'GET', '/v1/runs/' + run['id'])[0] == 404
    return {'oldEpoch': old_epoch, 'newEpoch': probe.info['serverId'], 'oldDaemonPid': old_pid,
            'oldUnitInactive': True, 'oldCgroupEmpty': True, 'oldRuntimeDirectoriesAbsent': True}


def handle(probe, consumer, flag, sequence, action):
    value = request(consumer / f'request-{sequence}.json', sequence, action, probe.info['serverId'])
    run = value['run']
    status, current = http(probe.socket, 'GET', '/v1/runs/' + run['id'])
    assert status == 200 and current['serverId'] == run['serverId']
    expected = 'closing' if action == 'recover-cleanup' else 'running'
    assert current['state'] == expected, 'Fault did not intercept the required live lifecycle state'
    unit, group = old_unit(probe, run)
    if expected == 'running':
        assert group, 'The observed running job has no owned control group'
        current_group = Path('/sys/fs/cgroup') / group.lstrip('/')
        assert any(path.read_text().strip() for path in current_group.rglob('cgroup.procs')), \
            'The command stopped before the controller injected its fault'
    observation = {'action': action, 'runId': run['id'], 'before': expected}
    if action == 'enable-cleanup-fault':
        assert not flag.exists()
        flag.write_text(run['id'])
        confirmed = False
    else:
        if action == 'recover-cleanup':
            assert flag.read_text() == run['id']
            status, refusal = http(probe.socket, 'POST', '/v1/runs/' + run['id'] + '/forget', {})
            assert (status, refusal['error']['code']) == (409, 'RUN_ACTIVE')
            observation['forgetRefusal'] = 'RUN_ACTIVE'
            flag.unlink()
        observation.update(crash_and_restart(probe, run, unit, group))
        confirmed = True
    probe.receipt['faults'].append(observation)
    probe.save()
    acknowledge(consumer, sequence, {'sequence': sequence, 'action': action,
                'previousServerId': run['serverId'], 'serverId': probe.info['serverId'],
                'containmentConfirmed': confirmed})


def stop_java(unit):
    call('systemctl', 'stop', unit, check=False)
    active = call('systemctl', 'show', unit, '-p', 'ActiveState', '--value', check=False).stdout.strip()
    assert active in ('', 'inactive', 'failed'), 'Java fixture observer survived cleanup'
    call('systemctl', 'reset-failed', unit, check=False)


def java(probe, args, runtime, consumer, flag):
    # This actor is outside the daemon's unit prefix, so daemon startup cleanup cannot kill the observer.
    unit = probe.name + 'j-recovery.service'
    command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
               '--slice', probe.slice, '-p', 'User=daemon', '-p', 'Group=daemon',
               '-p', 'MemoryMax=192M', '-p', 'MemorySwapMax=0', '-p', 'TasksMax=64',
               '-p', 'CPUQuota=50%', '-p', 'RuntimeMaxSec=240', '-p', 'TimeoutStopSec=10',
               '-p', 'KillMode=control-group', '-p', 'UMask=0077', '-p', 'Environment=LANG=C.UTF-8',
               str(args.java), '-Xms16m', '-Xmx96m', '-XX:MaxMetaspaceSize=128m', '-XX:ActiveProcessorCount=1',
               '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars/*'), MAIN,
               str(probe.socket), str(consumer)]
    with args.report.with_suffix('.stdout').open('x') as output, args.report.with_suffix('.stderr').open('x') as error:
        process = subprocess.Popen(command, stdout=output, stderr=error)
        try:
            deadline, completed = time.monotonic() + 250, 0
            while process.poll() is None:
                assert time.monotonic() < deadline, 'Java recovery fixture exceeded its total deadline'
                path = consumer / f'request-{completed + 1}.json'
                if path.exists():
                    assert completed < len(ACTIONS), 'Java requested an unplanned fault'
                    handle(probe, consumer, flag, completed + 1, ACTIONS[completed])
                    completed += 1
                time.sleep(.02)
            assert process.returncode == 0, 'Java recovery consumer failed; retained stdout/stderr identify the assertion'
            assert completed == len(ACTIONS), 'Java consumer omitted a planned fault'
        finally:
            stop_java(unit)
            if process.poll() is None:
                process.kill()
            process.wait(timeout=10)
    receipt = json.loads(ordinary(args.report.with_suffix('.stdout'), 1024 * 1024))
    assert receipt['result'] == 'PASS' and len(receipt['recoveries']) == 4 and len(receipt['checks']) == 5
    assert all(not row['oldTerminalObserved'] and not row['oldReplyReceiptConfirmed'] for row in receipt['recoveries'])
    assert all(row['controllerConfirmedOldContainment'] and row['fresh']['state'] == 'succeeded' for row in receipt['recoveries'])
    for name in ('calls', 'handler'):
        assert ordinary(consumer / (name + '.effect'), 128) == b'effect-once\n'
    return receipt


def run(args):
    runtime_hash = verify_runtime(args.runtime)
    assert (args.runtime / 'classes' / Path(*MAIN.split('.')).with_suffix('.class')).is_file()
    probe = Probe(args.binary, args.tools, args.report)
    config = probe.config.read_text()
    assert config.count('[service]\n') == 1 and config.count('network = "none"\n') == 1
    config = config.replace('[service]\n', '[service]\nmax_event_streams = 1\n')
    config = config.replace('network = "none"\n', 'network = "none"\nhost_calls = true\n')
    config = config.replace('timeout_millis = 10000\n', 'timeout_millis = 30000\n')
    probe.config.write_text(config)
    flag = fault_helper(probe)
    probe.receipt.update(scope='Java event and host-call observers during real daemon loss and cleanup faults; only owned fixture units, no product cutover or model calls',
                         controllerSha256=sha256(Path(__file__)), runtimeManifestSha256=runtime_hash,
                         faults=[], result='NOT_RUN')
    probe.save()
    try:
        probe.setup()
        assert probe.info['limits']['maxEventStreams'] == 1 and probe.info['profiles']['python']['hostCalls']
        assert probe.info['profiles']['python']['timeoutMillis'] == 30000
        runtime = probe.root / 'java-runtime'
        shutil.copytree(args.runtime, runtime)
        for path in [runtime, *runtime.rglob('*')]:
            path.chmod(0o755 if path.is_dir() else 0o644)
        consumer = probe.root / 'consumer'
        consumer.mkdir(mode=0o700)
        os.chown(consumer, *native_probe.CLIENT)
        probe.receipt['java'] = java(probe, args, runtime, consumer, flag)
        probe.receipt['result'] = 'PASS'
        probe.done('java-live-observers-reconcile-daemon-restart-without-effect-replay')
    except BaseException as failure:
        probe.receipt['result'] = 'FAIL'
        probe.receipt['failureType'] = type(failure).__name__
        args.report.with_suffix('.diagnostic.txt').write_text(str(failure) + '\n' +
            call('journalctl', '-u', probe.unit, '--no-pager', '-n', '80', check=False).stdout)
        raise
    finally:
        flag.unlink(missing_ok=True)
        try:
            probe.cleanup()
        except BaseException as failure:
            probe.receipt.update(result='FAIL', cleanup='FAIL', cleanupFailureType=type(failure).__name__)
            raise
        finally:
            probe.save()


def main():
    parser = argparse.ArgumentParser()
    for name in ('binary', 'tools', 'java', 'runtime', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    for name, value in vars(args).items():
        setattr(args, name, value.resolve())
    assert os.geteuid() == 0
    assert not args.report.exists(), 'Preserve every earlier probe attempt'
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.with_suffix('.attempt').open('x') as marker:
        marker.write('Exclusive Java native recovery fixture attempt\n')

    def interrupted(signum, frame):
        raise KeyboardInterrupt('Java recovery probe interrupted')

    signal.signal(signal.SIGTERM, interrupted)
    run(args)


if __name__ == '__main__':
    main()
