#!/usr/bin/env python3
"""Real native import and unchanged operator guards over synthetic offline legacy state."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tarfile
import time
import uuid

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(REPOSITORY / 'executor-service/rust/sandcrab'), str(REPOSITORY / 'executor-service'),
                str(REPOSITORY / 'acceptance/clients')]
from file_probe import FileProbe
from native_probe import Probe, call, http
from native_repository_fixture import verify_runtime
from native_migration_files import HeldLock
from native_migration_guard import require_stopped
from migration_probe_evidence import journal_preserved, operator_result, requests, require_execute, require_prepare, snapshot
from migration_probe_host import LegacyFixture, write


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def stop_operator(unit):
    call('systemctl', 'stop', unit, check=False)
    result = call('systemctl', 'show', unit, '-p', 'LoadState', '-p', 'ActiveState',
                  '-p', 'MainPID', '-p', 'ControlGroup', check=False)
    fields = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    assert fields.get('LoadState') in ('loaded', 'not-found'), 'Operator unit cleanup could not be observed'
    require_stopped(fields)


class MigrationProbe(FileProbe):
    def __init__(self, args):
        super().__init__(args.binary, args.tools, args.report)
        config = self.config.read_text().replace('[profiles.python]', '[profiles.repository]')
        config = config.replace('network = "none"\n', 'network = "none"\nhost_calls = true\ntransfer_bytes = 1048576\n', 1)
        self.config.write_text(config)
        self.legacy = None
        self.operator_units = []
        self.receipt.update(scope='Real Sandcrab files/volumes and app-UID migration helper with unchanged SourceGuard; synthetic legacy writer, Docker app and repository, no production migration',
                            controllerSha256=sha(Path(__file__)), fixtureSha256=sha(Path(__file__).with_name('migration_probe_host.py')),
                            sourceRevision=args.revision, result='NOT_RUN', observations={})

    def setup(self):
        call('mkfs.xfs', '-f', '-m', 'reflink=1', str(self.image))
        call('mount', '-o', 'loop,prjquota,nosuid,nodev', str(self.image), str(self.data))
        Probe.setup(self)
        assert self.info['profiles']['repository']['hostCalls']

    def unmount_fixture(self, mount):
        if self.legacy is not None and mount in (self.legacy.pool, self.legacy.mount):
            assert mount.is_relative_to(self.root) and not mount.is_symlink()
            call('umount', str(mount))
        else:
            super().unmount_fixture(mount)


def invoke(probe, args, phase, execute=False, reason=None, locked=False):
    legacy = probe.legacy
    prefix = args.report.with_name(args.report.stem + '-' + phase)
    trace = prefix.with_suffix('.trace')
    stdout, stderr = prefix.with_suffix('.stdout'), prefix.with_suffix('.stderr')
    assert not any(path.exists() for path in (trace, stdout, stderr)), 'Preserve every operator attempt'
    unit = probe.name + 'm-' + uuid.uuid4().hex[:8] + '.service'
    probe.operator_units.append(unit)
    command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
        '-p', 'MemoryMax=512M', '-p', 'MemorySwapMax=0', '-p', 'TasksMax=128', '-p', 'CPUQuota=50%',
        '-p', 'RuntimeMaxSec=180', '-p', 'TimeoutStopSec=15', '-p', 'KillMode=control-group',
        '-p', 'LimitFSIZE=48M', '-p', 'UMask=0077',
        str(args.strace), '-f', '-qq', '-s', '65536', '-e', 'trace=write,writev,sendto,sendmsg,flock,fcntl',
        '-o', str(trace), sys.executable, str(REPOSITORY / 'executor-service/migrate_native_repository.py'),
        '--request', str(legacy.request_path), '--operation', str(legacy.operation)]
    if execute:
        command.append('--execute')
    write(prefix.with_suffix('.launch.json'), {'unit': unit, 'phase': phase, 'execute': execute,
        'requestSha256': sha(legacy.request_path),
        'controllerSha256': sha(REPOSITORY / 'executor-service/migrate_native_repository.py')})
    with stdout.open('xb') as output, stderr.open('xb') as error:
        process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output, stderr=error)
        write(prefix.with_suffix('.process.json'), {'pid': process.pid, 'unit': unit,
            'startTicks': Path('/proc/' + str(process.pid) + '/stat').read_text().rsplit(')', 1)[1].split()[19]})
        try:
            code = process.wait(timeout=210)
        finally:
            stop_operator(unit)
            if process.poll() is None:
                process.wait(timeout=20)
    result = operator_result(stdout, stderr, code)
    observed = requests(trace)
    if reason is not None or locked:
        assert code != 0 and result['status'] == 'deferred', 'Unsafe source was admitted'
        if reason is not None:
            assert result.get('reason') == reason, 'Source was deferred for the wrong guard'
        if locked:
            assert re.search(r'(?:flock|fcntl)\(.*= -1 (?:EAGAIN|EACCES)', trace.read_text()), \
                'The expected real kernel lock refusal was not observed'
        assert not observed, 'A refused source reached the native service'
        assert legacy.journal('read') == legacy.before
        assert legacy.metadata_snapshot() == legacy.metadata_files
    else:
        assert code == 0 and result['status'] == ('published' if execute else 'prepared')
    probe.receipt['observations'][phase] = {'status': result['status'], 'nativeRequests': observed,
                                          'traceSha256': sha(trace), 'sourceRetained': result['sourceRetained']}
    probe.save()
    return trace


def guards(probe, args):
    legacy = probe.legacy
    deadline = time.monotonic() + 10
    while not (legacy.source / 'work/live-writer').exists():
        assert time.monotonic() < deadline, 'Synthetic writer never wrote its owned source file'
        time.sleep(.05)
    invoke(probe, args, 'running-app', reason='APPLICATION_ACTIVE')
    call('docker', 'stop', '--time', '5', legacy.container)
    invoke(probe, args, 'live-writer', reason='LEGACY_WRITER_ACTIVE')
    legacy.stop_legacy()
    probe.done('real-running-docker-app-and-systemd-writer-refuse-migration')

    call('mount', '--bind', str(legacy.source / 'work'), str(legacy.mount))
    try:
        invoke(probe, args, 'source-mount', reason='SOURCE_STILL_MOUNTED')
    finally:
        call('umount', str(legacy.mount))
    probe.done('actual-source-bind-mount-refuses-migration')

    for label, path in (('deployment-lock', legacy.installation / '.deployment/lock'),
                        ('source-lock', legacy.source / '.lease.lock')):
        with HeldLock(path):
            invoke(probe, args, label, locked=True)
    probe.done('actual-deployment-and-source-flocks-refuse-reentry')


def migration(probe, args):
    legacy = probe.legacy
    original = snapshot(legacy.source)
    write(args.report.with_suffix('.source-before.json'), original)
    trace = invoke(probe, args, 'prepare')
    require_prepare(trace)
    assert legacy.journal('read') == legacy.before
    assert legacy.metadata_snapshot() == legacy.metadata_files
    assert snapshot(legacy.source) == original
    status, inventory = http(probe.socket, 'GET', '/v1/volumes')
    assert status == 200 and inventory['volumes'] == []
    probe.done('prepare-observes-real-info-without-post-or-journal-mutation')

    blocker = legacy.journal('lock', wait=False)
    try:
        invoke(probe, args, 'journal-lock', execute=True, reason='HELPER_UNCONFIRMED', locked=True)
    finally:
        blocker.stdin.close()
        assert blocker.wait(timeout=15) == 0
    probe.done('real-app-uid-journal-lock-refuses-concurrent-publisher')

    trace = invoke(probe, args, 'execute', execute=True)
    plan = json.loads((legacy.operation / 'plan.json').read_text())
    require_execute(trace, plan)
    after = legacy.journal('read')
    journal_preserved(legacy.before, after)
    assert after['state']['originalCommit'] == legacy.original_commit
    assert after['state']['baseCommit'] == legacy.base_commit
    assert after['writer']['workerBootId'] == probe.info['serverId']
    assert after['writer'] == plan['writer']
    current_files = legacy.metadata_snapshot()
    assert current_files.keys() == legacy.metadata_files.keys()
    changed = [name for name in current_files if current_files[name] != legacy.metadata_files[name]]
    assert len(changed) == 1 and changed[0].endswith('.account'), 'Import changed immutable original/baseline bytes'
    assert snapshot(legacy.source) == original
    destination = probe.data / 'volumes' / legacy.copy / 'data'
    assert snapshot(destination) == original
    assert {path.name for path in destination.iterdir()} == {'work', 'home'}
    status, volume = http(probe.socket, 'GET', '/v1/volumes/' + legacy.copy)
    assert status == 200 and volume['state'] == 'ready' and not volume['busy'] and volume['attachedTo'] is None
    write(args.report.with_suffix('.journal-before.json'), legacy.before)
    write(args.report.with_suffix('.journal-after.json'), after)
    write(args.report.with_suffix('.metadata-before.json'), legacy.metadata_files)
    write(args.report.with_suffix('.metadata-after.json'), current_files)
    write(args.report.with_suffix('.destination.json'), snapshot(destination))
    probe.done('real-native-import-preserves-work-home-links-modes-originals-and-every-journal-field')

    replay = invoke(probe, args, 'confirmed-replay', execute=True)
    assert requests(replay) == [] and legacy.journal('read') == after
    assert snapshot(legacy.source) == original and snapshot(destination) == original
    probe.done('confirmed-fixed-plan-replay-does-not-resend-native-imports')


def retain_fixture(probe, args):
    legacy = probe.legacy
    if legacy is None:
        return
    if legacy.operation.exists():
        shutil.copytree(legacy.operation, args.report.with_suffix('.operation'), symlinks=True)
    with tarfile.open(args.report.with_suffix('.retained-source.tar'), 'x', dereference=False) as archive:
        for path, name in ((legacy.source, 'legacy-copy'), (legacy.metadata, 'journal')):
            if path.exists():
                archive.add(path, arcname=name)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'binary', 'tools', 'java', 'strace', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--container-image', required=True, help='An already-present pinned image with /bin/sleep; never pulled')
    args = parser.parse_args()
    for name in ('runtime', 'binary', 'tools', 'java', 'strace'):
        setattr(args, name, getattr(args, name).resolve(strict=True))
    args.report = args.report.resolve()
    assert os.geteuid() == 0 and re.fullmatch('[0-9a-f]{40}', args.revision)
    os.umask(0o077)
    assert not args.report.exists(), 'Preserve every previous fixture attempt'
    assert re.fullmatch(r'(?:sha256:[0-9a-f]{64}|[^\s]+@sha256:[0-9a-f]{64})', args.container_image)
    call('docker', 'image', 'inspect', args.container_image)
    call(str(args.strace), '--version')
    runtime_hash = verify_runtime(args.runtime)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    write(args.report.with_suffix('.attempt'), 'One real native migration fixture; no automatic rerun\n')
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Native migration fixture interrupted')
    def expired(signum, frame):
        raise TimeoutError('Native migration fixture exceeded its work or cleanup deadline')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, expired)
    probe = MigrationProbe(args)
    probe.receipt['runtimeManifestSha256'] = runtime_hash
    probe.save()
    write(args.report.with_suffix('.identity.json'), {'root': str(probe.root), 'unit': probe.unit,
                                                    'slice': probe.slice, 'sourceRevision': args.revision})
    try:
        signal.alarm(900)
        probe.setup()
        probe.legacy = LegacyFixture(probe, args)
        write(args.report.with_suffix('.legacy-identity.json'), {'container': probe.legacy.container,
            'label': probe.legacy.token, 'units': [probe.legacy.supervisor, probe.legacy.job, probe.legacy.slice],
            'copy': probe.legacy.copy, 'source': str(probe.legacy.source)})
        probe.legacy.setup()
        assert verify_runtime(probe.legacy.java_runtime) == runtime_hash
        guards(probe, args)
        migration(probe, args)
        probe.receipt['result'] = 'PASS'
    except BaseException as failure:
        probe.receipt.update(result='FAIL', failureType=type(failure).__name__)
        args.report.with_suffix('.diagnostic.txt').write_text(str(failure))
        raise
    finally:
        signal.alarm(180)
        try:
            for unit in probe.operator_units:
                stop_operator(unit)
            if probe.legacy is not None:
                probe.legacy.close()
            retain_fixture(probe, args)
            probe.cleanup()
        except BaseException as failure:
            probe.receipt.update(result='FAIL', cleanup='FAIL', cleanupFailureType=type(failure).__name__)
            if probe.root.exists():
                probe.root.chmod(0o700)
            raise
        finally:
            signal.alarm(0)
            probe.save()


if __name__ == '__main__':
    main()
