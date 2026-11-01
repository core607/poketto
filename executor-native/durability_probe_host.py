"""Own and observe only the serial Java processes of one native durability fixture."""
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

from native_probe import call, http
from durability_probe_evidence import barrier, disk_checkpoint, json_file, native_checkpoint

MAIN = 'io.github.core607.poketto.executor.internal.NativeDurabilityProcessProbe'


def save(path, value):
    with path.open('x', encoding='utf-8') as output:
        json.dump(value, output, indent=2)
        output.write('\n')


def unit_state(unit):
    result = call('systemctl', 'show', unit, '-p', 'LoadState', '-p', 'ActiveState',
                  '-p', 'MainPID', '-p', 'ControlGroup', check=False)
    fields = dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)
    assert fields.get('LoadState') in ('loaded', 'not-found'), 'Systemd identity could not be observed'
    return fields


def empty_group(group):
    if not group:
        return
    assert group.startswith('/') and '..' not in Path(group).parts
    path = Path('/sys/fs/cgroup') / group.lstrip('/')
    if path.exists():
        values = dict(line.split() for line in (path / 'cgroup.events').read_text().splitlines())
        assert values['populated'] == '0', 'Owned cgroup still contains processes'


def stop_unit(unit, old=None):
    call('systemctl', 'stop', unit, check=False)
    value = unit_state(unit)
    assert value.get('ActiveState') in ('inactive', 'failed') and value.get('MainPID', '0') == '0'
    empty_group(value.get('ControlGroup'))
    if old is not None:
        empty_group(old['cgroup'])
        path = Path('/proc') / str(old['pid']) / 'stat'
        if path.exists():
            assert path.read_text().rsplit(')', 1)[1].split()[19] != old['startTicks']


def identity(unit, java):
    value = unit_state(unit)
    pid = int(value['MainPID'])
    assert value['ActiveState'] == 'active' and pid > 1
    process = Path('/proc') / str(pid)
    assert (process / 'exe').resolve(strict=True) == java
    group = value['ControlGroup']
    assert '0::' + group in (process / 'cgroup').read_text().splitlines()
    assert MAIN.encode() in (process / 'cmdline').read_bytes().split(b'\0')
    return {'unit': unit, 'pid': pid, 'startTicks': (process / 'stat').read_text().rsplit(')', 1)[1].split()[19],
            'cgroup': group}


def kill(unit, java, expected):
    descriptor = os.pidfd_open(expected['pid'])
    try:
        assert identity(unit, java) == expected, 'Java process changed before the authorized fixture signal'
        signal.pidfd_send_signal(descriptor, signal.SIGKILL)
    finally:
        os.close(descriptor)


def git_state(case, before):
    repository = case / 'authority/remotes' / (before['owner']['workspaceId'] + '.git')
    assert repository.is_dir() and not repository.is_symlink()
    command = ['git', '-c', 'safe.directory=' + str(repository), '--git-dir=' + str(repository)]
    commit = call(*command, 'rev-parse', 'refs/heads/main').stdout.strip()
    count = int(call(*command, 'rev-list', '--count', 'refs/heads/main').stdout.strip())
    return {'commit': commit, 'commits': count}


class JavaPhases:
    def __init__(self, probe, args, runtime, config, consumer):
        self.probe, self.args, self.runtime = probe, args, runtime
        self.config, self.consumer, self.units = config, consumer, []
        base = runtime / 'classes/io/github/core607/poketto/executor/internal'
        self.classes = {label: hashlib.sha256((base / name).read_bytes()).hexdigest() for label, name in
                        (('probe', 'NativeDurabilityProcessProbe.class'), ('executor', 'NativeRepositoryExecutor.class'))}

    def checkpoint(self, scenario, observed):
        case = self.consumer / scenario
        ready, before = json_file(case / 'ready.json', 16384), json_file(case / 'before.json')
        barrier(ready, before, scenario, observed['pid'], self.probe.info['serverId'], self.classes)
        disk = disk_checkpoint(self.probe.journal / 'metadata' / scenario, before)
        status, volume = http(self.probe.socket, 'GET', '/v1/volumes/' + ready['copyId'])
        assert status == 200
        status, session = http(self.probe.socket, 'GET', '/v1/sessions/' + ready['writer']['leaseId'])
        if status == 404 and scenario == 'discarding':
            assert session['error']['code'] == 'SESSION_NOT_FOUND'
            session = None
        else:
            assert status == 200
        run = None
        if scenario in ('uncertain', 'beforepublish', 'afterpublish'):
            status, run = http(self.probe.socket, 'GET', '/v1/runs/' + ready['commandId'])
            assert status == 200
        native_checkpoint(scenario, ready, session, volume, run)
        if scenario == 'discarding':
            self.probe.guests_contained()
        git = git_state(case, before)
        assert git['commit'] == (ready['candidateCommit'] or before['state']['baseCommit'])
        evidence = {'barrier': ready, 'disk': disk, 'nativeSession': session, 'nativeVolume': volume,
                    'nativeRun': run, 'remote': git, 'jvm': observed}
        save(self.args.report.with_suffix('.' + scenario + '-checkpoint.json'), evidence)
        return evidence

    def run(self, scenario, mode):
        unit = self.probe.name + 'd-' + scenario + '-' + mode + '.service'
        self.units.append(unit)
        prefix = self.args.report.with_name(self.args.report.stem + '-' + scenario + '-' + mode)
        agent, = self.runtime.glob('jars/byte-buddy-agent-*.jar')
        command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
            '-p', 'User=daemon', '-p', 'Group=daemon', '-p', 'MemoryMax=384M', '-p', 'MemorySwapMax=0',
            '-p', 'CPUQuota=50%', '-p', 'TasksMax=96', '-p', 'RuntimeMaxSec=180', '-p', 'TimeoutStopSec=15',
            '-p', 'KillMode=control-group', '-p', 'UMask=0077', '-p', 'Environment=LANG=C.UTF-8',
            str(self.args.java), '-Xms16m', '-Xmx160m', '-XX:MaxMetaspaceSize=160m', '-XX:ActiveProcessorCount=1',
            '-Djava.io.tmpdir=' + str(self.probe.journal / 'tmp'), '-Duser.home=' + str(self.consumer),
            '-javaagent:' + str(agent), '-cp', str(self.runtime / 'classes') + ':' + str(self.runtime / 'jars/*'),
            MAIN, str(self.config), scenario, mode]
        save(prefix.with_suffix('.launch.json'), {'unit': unit, 'scenario': scenario, 'mode': mode, 'classes': self.classes})
        observed, checkpoint = None, None
        with prefix.with_suffix('.stdout').open('xb') as output, prefix.with_suffix('.stderr').open('xb') as error:
            process = subprocess.Popen(command, stdin=subprocess.DEVNULL, stdout=output, stderr=error)
            save(prefix.with_suffix('.wrapper.json'), {'pid': process.pid, 'startTicks':
                (Path('/proc') / str(process.pid) / 'stat').read_text().rsplit(')', 1)[1].split()[19]})
            deadline = time.monotonic() + 200
            try:
                while process.poll() is None:
                    assert time.monotonic() < deadline, 'Original Java phase exceeded its fixed deadline'
                    if observed is None and int(unit_state(unit).get('MainPID', '0')) > 1:
                        observed = identity(unit, self.args.java)
                        save(prefix.with_suffix('.process.json'), observed)
                    ready = self.consumer / scenario / 'ready.json'
                    if mode == 'produce' and checkpoint is None and ready.exists():
                        assert observed is not None
                        checkpoint = self.checkpoint(scenario, observed)
                        kill(unit, self.args.java, observed)
                    time.sleep(.02)
            finally:
                stop_unit(unit, observed)
                if process.poll() is None:
                    process.wait(timeout=20)
        assert observed is not None, 'The actual application JVM was not observed'
        if mode == 'produce':
            assert checkpoint is not None and process.returncode != 0, 'Producer escaped external SIGKILL'
            return checkpoint
        assert process.returncode == 0, 'Resume JVM failed; preserve its exact stdout/stderr'
        result = json_file(self.consumer / scenario / 'result.json', 16384)
        assert result['scenario'] == scenario and result['result'] == 'PASS'
        assert result['probeClassSha256'] == self.classes['probe'] and result['executorClassSha256'] == self.classes['executor']
        return result

    def close(self):
        failures = []
        for unit in self.units:
            try:
                stop_unit(unit)
            except TimeoutError:
                raise
            except Exception as failure:
                failures.append(type(failure).__name__)
        assert not failures, 'Owned Java cleanup remains unconfirmed: ' + ','.join(failures)
