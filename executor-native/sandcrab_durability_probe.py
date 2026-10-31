#!/usr/bin/env python3
"""Five native-runtime process durability windows on two disposable bounded XFS pools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import sys
import tarfile

REPOSITORY = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(REPOSITORY / 'executor-service/rust/sandcrab'), str(REPOSITORY / 'acceptance/clients')]
import native_probe
from native_probe import Probe, call, http
from volume_probe import VolumeProbe
from native_repository_fixture import verify_runtime
from durability_probe_evidence import SCENARIOS, json_file
from durability_probe_host import JavaPhases, empty_group, git_state, save, stop_unit, unit_state


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class DurabilityProbe(VolumeProbe):
    def __init__(self, args):
        super().__init__(args.binary, args.tools, args.report)
        self.journal = self.root / 'journal-pool'
        self.journal.mkdir(mode=0o755)
        self.journal.chmod(0o755)
        self.journal_image = self.root / 'journal.xfs'
        with self.journal_image.open('xb') as output:
            output.truncate(512 * 1024 * 1024)
        config = self.config.read_text().replace('[profiles.python]', '[profiles.repository]')
        config = config.replace('network = "none"\n', 'network = "none"\nhost_calls = true\ntransfer_bytes = 1048576\n', 1)
        config = config.replace('timeout_millis = 10000', 'timeout_millis = 60000', 1)
        config = config.replace('session_ttl_millis = 60000', 'session_ttl_millis = 120000', 1)
        config = config.replace('output_limit_bytes = 4096', 'output_limit_bytes = 65536', 1)
        self.config.write_text(config)
        self.receipt.update(scope='Real native application runtime, JGit authority, XFS journal and external JVM SIGKILL; synthetic fixed authorization, not HTTP or production acceptance',
                            sourceRevision=args.revision, controllerSha256=sha(Path(__file__)),
                            hostSha256=sha(Path(__file__).with_name('durability_probe_host.py')),
                            observationSha256=sha(Path(__file__).with_name('durability_probe_evidence.py')),
                            result='NOT_RUN', cases={})

    def setup(self):
        for image, target in ((self.image, self.data), (self.journal_image, self.journal)):
            call('mkfs.xfs', '-f', '-m', 'reflink=1', str(image))
            call('mount', '-o', 'loop,prjquota,nosuid,nodev', str(image), str(target))
            target.chmod(0o755)
        for name in ('consumer', 'metadata', 'exports', 'transfers', 'tmp'):
            path = self.journal / name
            path.mkdir(mode=0o700)
            os.chown(path, *native_probe.CLIENT)
        Probe.setup(self)
        assert self.info['profiles']['repository']['hostCalls']
        assert self.info['profiles']['repository']['transferBytes'] == 1048576
        service = unit_state(self.unit)
        self.native_pid = int(service['MainPID'])
        assert service['ActiveState'] == 'active' and self.native_pid > 1
        process = Path('/proc') / str(self.native_pid)
        assert (process / 'exe').resolve(strict=True) == self.binary
        self.native_start = (process / 'stat').read_text().rsplit(')', 1)[1].split()[19]
        self.receipt['nativeProcess'] = {'unit': self.unit, 'pid': self.native_pid,
                                        'startTicks': self.native_start, 'cgroup': service['ControlGroup']}
        self.save()

    def guests_contained(self):
        service = unit_state(self.unit)
        assert service['ActiveState'] == 'active' and int(service['MainPID']) == self.native_pid
        process = Path('/proc') / str(self.native_pid) / 'stat'
        assert process.read_text().rsplit(')', 1)[1].split()[19] == self.native_start
        pool = unit_state(self.slice)['ControlGroup']
        assert pool and service['ControlGroup'].startswith(pool + '/')
        base = Path('/sys/fs/cgroup') / pool.lstrip('/')
        for path in base.rglob('cgroup.procs'):
            group = '/' + path.parent.relative_to('/sys/fs/cgroup').as_posix()
            if group == service['ControlGroup'] or group.startswith(service['ControlGroup'] + '/'):
                continue
            assert not path.read_text().strip(), 'Guest processes survived the application recovery/disposal'

    def release_terminal_receipts(self, scenario, report):
        assert http(self.socket, 'GET', '/v1/info')[1]['serverId'] == self.info['serverId']
        status, volumes = http(self.socket, 'GET', '/v1/volumes')
        assert status == 200 and volumes['volumes'] == [], 'Consumer did not release its exact native volumes'
        self.guests_contained()
        inventory = {}
        for kind in ('sessions', 'runs'):
            status, listed = http(self.socket, 'GET', '/v1/' + kind)
            assert status == 200 and listed['serverId'] == self.info['serverId'] and listed['nextAfter'] is None
            inventory[kind] = []
            for identifier in listed['ids']:
                status, value = http(self.socket, 'GET', '/v1/' + kind + '/' + identifier)
                assert status == 200
                if kind == 'sessions':
                    assert value['state'] in ('closed', 'expired', 'failed')
                else:
                    assert value['state'] not in ('running', 'closing')
                inventory[kind].append(value)
                status, forgotten = http(self.socket, 'POST', '/v1/' + kind + '/' + identifier + '/forget', {})
                assert status == 200 and forgotten['forgotten']
        save(report.with_suffix('.' + scenario + '-contained.json'), inventory)

    def contain_native(self):
        stop_unit(self.unit)
        rows = call('systemctl', 'list-units', '--all', '--plain', '--no-legend', self.name + '-*.service').stdout
        for line in rows.splitlines():
            unit = line.split()[0]
            assert unit.startswith(self.name + '-') and unit.endswith('.service')
            stop_unit(unit)
        empty_group(unit_state(self.slice).get('ControlGroup'))

    def unmount_fixture(self, mount):
        if mount == self.journal:
            assert mount.parent == self.root and not mount.is_symlink()
            call('umount', str(mount))
        else:
            super().unmount_fixture(mount)


def prepare_java(probe, args):
    runtime = probe.root / 'java-runtime'
    shutil.copytree(args.runtime, runtime)
    for path in [runtime, *runtime.rglob('*')]:
        path.chmod(0o755 if path.is_dir() else 0o644)
    assert verify_runtime(runtime) == verify_runtime(args.runtime)
    config = probe.journal / 'consumer/java.json'
    save(config, {'socket': str(probe.socket), 'root': str(probe.journal / 'consumer'),
                  'metadata': str(probe.journal / 'metadata'), 'exports': str(probe.journal / 'exports'),
                  'transfers': str(probe.journal / 'transfers')})
    os.chown(config, *native_probe.CLIENT)
    return JavaPhases(probe, args, runtime, config, probe.journal / 'consumer')


def retain(probe, report):
    with tarfile.open(report.with_suffix('.retained-fixture.tar'), 'x', dereference=False) as archive:
        if probe.journal.exists():
            archive.add(probe.journal, arcname='journal-pool')
        if probe.storage.exists():
            archive.add(probe.storage, arcname='native-storage')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'binary', 'tools', 'java', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0 and re.fullmatch('[0-9a-f]{40}', args.revision)
    for name in ('runtime', 'binary', 'tools', 'java'):
        setattr(args, name, getattr(args, name).resolve(strict=True))
    args.report = args.report.resolve()
    os.umask(0o077)
    assert not args.report.exists()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    save(args.report.with_suffix('.attempt'), {'sourceRevision': args.revision, 'automaticRetry': False})
    runtime_hash = verify_runtime(args.runtime)
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Durability fixture interrupted; contain the original attempt')
    def expired(signum, frame):
        raise TimeoutError('Durability fixture exceeded its work or cleanup deadline')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, expired)
    probe, phases = DurabilityProbe(args), None
    probe.receipt['runtimeManifestSha256'] = runtime_hash
    probe.save()
    save(args.report.with_suffix('.identity.json'), {'root': str(probe.root), 'unit': probe.unit, 'slice': probe.slice})
    try:
        signal.alarm(1500)
        probe.setup()
        phases = prepare_java(probe, args)
        for scenario in SCENARIOS:
            checkpoint = phases.run(scenario, 'produce')
            resumed = phases.run(scenario, 'resume')
            after = git_state(phases.consumer / scenario, json_file(phases.consumer / scenario / 'before.json'))
            assert after == checkpoint['remote'], 'Resume changed the successful authority or repeated a remote push'
            probe.release_terminal_receipts(scenario, args.report)
            probe.receipt['cases'][scenario] = {'result': 'PASS', 'barrierPhase': checkpoint['disk']['phase'],
                'externalJvmKill': True, 'remoteUnchangedOnResume': True, 'nativeEpoch': probe.info['serverId'],
                'probeClassSha256': resumed['probeClassSha256'], 'executorClassSha256': resumed['executorClassSha256']}
            probe.done('native-process-durability-' + scenario)
        probe.receipt['result'] = 'PASS'
    except BaseException as failure:
        probe.receipt.update(result='FAIL', failureType=type(failure).__name__)
        args.report.with_suffix('.diagnostic.txt').write_text(str(failure))
        raise
    finally:
        signal.alarm(180)
        try:
            if phases is not None:
                phases.close()
            probe.contain_native()
            retain(probe, args.report)
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
