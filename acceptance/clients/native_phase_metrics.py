"""Bounded JFR and cgroup observations for the disposable native HTTP fixture."""

from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

EVENT = 'poketto.NativeRepositoryPhase'
JFC = f'''<?xml version="1.0" encoding="UTF-8"?>
<configuration version="2.0" label="Native phases" description="Synthetic fixture phases only" provider="Poketto">
  <event name="{EVENT}">
    <setting name="enabled">true</setting>
    <setting name="threshold">0 ns</setting>
    <setting name="stackTrace">false</setting>
  </event>
  <event name="jdk.DataLoss"><setting name="enabled">true</setting></event>
  <event name="poketto.RepositoryReadResolution"><setting name="enabled">false</setting></event>
</configuration>
'''


def run(command, **kwargs):
    return subprocess.run(command, check=True, capture_output=True, text=True, timeout=30, **kwargs).stdout


def unit_fields(unit):
    output = run(['systemctl', 'show', unit, '-p', 'ControlGroup', '-p', 'MainPID', '-p', 'ActiveState'])
    fields = dict(line.split('=', 1) for line in output.splitlines() if '=' in line)
    assert fields['ActiveState'] == 'active', 'Owned measurement unit is not active'
    return fields


def group_path(unit):
    relative = unit_fields(unit)['ControlGroup']
    assert relative.startswith('/') and '..' not in Path(relative).parts
    path = Path('/sys/fs/cgroup' + relative)
    assert path.resolve(strict=True) == path
    return path


def native_groups(unit, pool):
    service = group_path(unit)
    assert service.parent == pool
    pid = int(unit_fields(unit)['MainPID'])
    membership = (Path('/proc') / str(pid) / 'cgroup').read_text().strip().split(':', 2)
    assert membership[:2] == ['0', ''] and membership[2].startswith('/')
    daemon = Path('/sys/fs/cgroup' + membership[2])
    workloads = daemon.parent / 'workloads'
    assert daemon.resolve(strict=True) == daemon and daemon.is_relative_to(service) and daemon.name == 'daemon'
    assert workloads.resolve(strict=True) == workloads and workloads.is_dir()
    return {'nativePool': pool, 'daemon': daemon, 'workloads': workloads}


def pairs(path):
    return {key: int(value) for key, value in (line.split() for line in path.read_text().splitlines())}


def snapshot(groups):
    result = {'wallNanos': time.time_ns(), 'monotonicNanos': time.monotonic_ns(), 'groups': {}}
    for name, path in groups.items():
        pressure = {}
        for kind in ('cpu', 'io', 'memory'):
            for line in (path / (kind + '.pressure')).read_text().splitlines():
                scope, *fields = line.split()
                values = dict(field.split('=') for field in fields)
                pressure[kind + '.' + scope + '.totalMicros'] = int(values['total'])
        io_file = path / 'io.stat'
        io = {} if io_file.is_file() else None
        if io is None:
            assert name in ('daemon', 'workloads'), 'The application or pool lacks required I/O accounting'
        else:
            for line in io_file.read_text().splitlines():
                device, *fields = line.split()
                io[device] = {key: int(value) for key, value in (field.split('=') for field in fields)}
        result['groups'][name] = {
            'cpu': pairs(path / 'cpu.stat'), 'memory': pairs(path / 'memory.stat'),
            'memoryEvents': pairs(path / 'memory.events'), 'pressure': pressure, 'io': io,
            'memoryCurrent': int((path / 'memory.current').read_text()),
            'limits': {key: (path / key).read_text().strip()
                       for key in ('cpu.max', 'memory.max', 'memory.swap.max', 'pids.max')},
        }
    return result


def resource_delta(before, after):
    result = {}
    for group, start in before['groups'].items():
        end = after['groups'][group]
        assert start['limits'] == end['limits'], 'Resource limits changed during a measured request'
        delta = {}
        for field in ('cpu', 'memoryEvents', 'pressure'):
            delta[field] = {key: end[field][key] - value for key, value in start[field].items()}
            assert all(value >= 0 for value in delta[field].values()), 'A cumulative cgroup counter reset'
        assert (start['io'] is None) == (end['io'] is None), 'I/O accounting changed during a request'
        delta['io'] = {key: None if start['io'] is None else
                      sum(device.get(key, 0) for device in end['io'].values())
                      - sum(device.get(key, 0) for device in start['io'].values())
                      for key in ('rbytes', 'wbytes', 'rios', 'wios')}
        assert all(value is None or value >= 0 for value in delta['io'].values()), 'An I/O counter reset'
        delta['memoryBefore'] = {key: start['memory'][key] for key in ('anon', 'file', 'kernel')}
        delta['memoryAfter'] = {key: end['memory'][key] for key in ('anon', 'file', 'kernel')}
        delta['memoryCurrentBefore'] = start['memoryCurrent']
        delta['memoryCurrentAfter'] = end['memoryCurrent']
        result[group] = delta
    return result


def timestamp(value):
    match = re.fullmatch(r'(\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d)(?:\.(\d{1,9}))?(Z|[+-]\d\d:\d\d)', value)
    assert match, 'Unrecognized JFR timestamp'
    instant = datetime.fromisoformat(match[1] + match[3].replace('Z', '+00:00'))
    epoch = datetime(1970, 1, 1, tzinfo=timezone.utc)
    return int((instant - epoch).total_seconds()) * 1_000_000_000 + int((match[2] or '').ljust(9, '0'))


def duration(value):
    match = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)(?:\.(\d{1,9}))?S)?', value)
    assert match and any(match.groups()), 'Unrecognized JFR duration'
    seconds = int(match[1] or 0) * 3600 + int(match[2] or 0) * 60 + int(match[3] or 0)
    return seconds * 1_000_000_000 + int((match[4] or '').ljust(9, '0'))


def correlate(document, cases):
    groups = {}
    for event in document['recording']['events']:
        assert event['type'] in (EVENT, 'jdk.DataLoss'), 'The recording contains an unapproved event type'
        value = event['values']
        if event['type'] == 'jdk.DataLoss':
            assert value['amount'] == 0, 'JFR lost events'
            continue
        assert set(value) == {'startTime', 'duration', 'eventThread', 'stackTrace', 'invocation', 'phase',
                              'parentPhase', 'depth', 'threadCpuNanos', 'transferredBytes'}
        assert value['stackTrace'] is None
        assert re.fullmatch('[A-Z_]{1,40}', value['phase'])
        assert value['parentPhase'] is None or re.fullmatch('[A-Z_]{1,40}', value['parentPhase'])
        start = timestamp(value['startTime'])
        item = {key: value[key] for key in ('phase', 'parentPhase', 'depth', 'threadCpuNanos', 'transferredBytes')}
        item.update(startNanos=start, wallNanos=duration(value['duration']))
        groups.setdefault(value['invocation'], []).append(item)
    requests = []
    for events in groups.values():
        roots = [event for event in events if event['phase'] == 'REQUEST' and event['depth'] == 0]
        if not roots:
            continue  # Background containment has its own root and is not charged to a request.
        assert len(roots) == 1
        root = roots[0]
        assert all(event['startNanos'] >= root['startNanos'] and
                   event['startNanos'] + event['wallNanos'] <= root['startNanos'] + root['wallNanos']
                   for event in events), 'A request does not contain its nested phases'
        requests.append((root, events))
    assert len(requests) == len(cases), 'Missing or unexpected request phases'
    unused = list(requests)
    for case in cases:
        matching = [(root, events) for root, events in unused
                    if root['startNanos'] >= case['startWallNanos'] - 1_000_000 and
                    root['startNanos'] + root['wallNanos'] <= case['endWallNanos'] + 1_000_000]
        assert len(matching) == 1, 'Cannot uniquely associate the JFR request with its HTTP outcome'
        root, events = matching[0]
        unused.remove(matching[0])
        case['phases'] = sorted(events, key=lambda event: (event['startNanos'], event['depth']))
    return cases


class Recording:
    def __init__(self, root, java, unit, evidence):
        import pwd

        match = re.fullmatch(r'poketto-client-([a-f0-9]{8})-app', unit)
        assert match and root.resolve() == root and root.parent == Path('/var/lib')
        assert root.name.startswith('poketto-client-') and root.stat().st_uid == 0
        self.account = pwd.getpwnam('pkt-capp-' + match[1])
        self.pid = int(unit_fields(unit)['MainPID'])
        process = Path('/proc') / str(self.pid)
        assert self.pid > 1 and process.stat().st_uid == self.account.pw_uid
        assert (process / 'exe').resolve() == java.resolve()
        arguments = (process / 'cmdline').read_bytes().split(b'\0')
        assert b'io.github.core607.poketto.acceptance.AcceptanceApplication' in arguments
        assert ('-Duser.home=' + str(root / 'home')).encode() in arguments
        self.java, self.evidence = java, evidence
        self.home = root / 'home'
        self.source = self.home / 'native-phases.jfr'
        self.settings = self.home / 'native-phases.jfc'
        self.active = False

    def command(self, *arguments):
        return run([str(self.java.with_name('jcmd')), str(self.pid), *arguments],
                   user=self.account.pw_uid, group=self.account.pw_gid, extra_groups=[], cwd=self.home)

    def start(self):
        with self.settings.open('x') as stream:
            stream.write(JFC)
        self.settings.chmod(0o600)
        os.chown(self.settings, self.account.pw_uid, self.account.pw_gid)
        response = self.command('JFR.start', 'name=native-phases', 'settings=' + str(self.settings),
                                'maxsize=16m', 'duration=900s', 'dumponexit=false', 'filename=' + str(self.source))
        (self.evidence / 'jfr-start.log').write_text(response)
        assert 'Started recording' in response, 'The phase recording did not start'
        self.active = True

    def stop(self):
        if not self.active:
            return None
        response = self.command('JFR.stop', 'name=native-phases', 'filename=' + str(self.source))
        self.active = False
        (self.evidence / 'jfr-stop.log').write_text(response)
        assert self.source.is_file() and 0 < self.source.stat().st_size <= 32 * 1024 * 1024
        target = self.evidence / 'phases.jfr'
        shutil.copyfile(self.source, target)
        output = self.evidence / 'phases.json'
        with output.open('x') as stream:
            subprocess.run([self.java.with_name('jfr'), 'print', '--json', target],
                           stdout=stream, stderr=subprocess.PIPE, check=True, timeout=30)
        assert output.stat().st_size <= 32 * 1024 * 1024, 'Expanded phase events exceeded their bound'
        return json.loads(output.read_text())
