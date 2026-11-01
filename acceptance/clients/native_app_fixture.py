"""Private control of only the app JVM launched by one disposable consumer fixture."""
import json
import math
import os
from pathlib import Path
import signal
import stat
import time
import urllib.request

from native_repository_fixture import run, stop_unit


def publish(path, value):
    temporary = path.with_suffix('.pending')
    descriptor = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    with os.fdopen(descriptor, 'w', encoding='utf-8') as output:
        json.dump(value, output)
        output.flush()
        os.fsync(output.fileno())
    os.link(temporary, path, follow_symlinks=False)
    temporary.unlink()


def read_private(path):
    info = path.lstat()
    assert stat.S_ISREG(info.st_mode) and info.st_uid == os.geteuid() and stat.S_IMODE(info.st_mode) == 0o600
    assert info.st_size <= 4096, 'Fixture control request exceeded its bound'
    return json.loads(path.read_text())


def process_identity(unit):
    fields = dict(line.split('=', 1) for line in run(['systemctl', 'show', unit, '-p', 'MainPID',
        '-p', 'ActiveState', '-p', 'ControlGroup']).splitlines())
    pid = int(fields['MainPID'])
    assert fields['ActiveState'] == 'active' and pid > 1
    group = fields['ControlGroup']
    assert group.startswith('/') and '..' not in Path(group).parts
    assert ('0::' + group) in (Path('/proc') / str(pid) / 'cgroup').read_text().splitlines()
    start = (Path('/proc') / str(pid) / 'stat').read_text().rsplit(')', 1)[1].split()[19]
    return {'unit': unit, 'pid': pid, 'startTicks': start, 'cgroup': group}


def kill_exact(expected):
    # The pidfd pins this process even if the numeric PID is reused after the readback.
    with os.fdopen(os.pidfd_open(expected['pid']), 'rb', closefd=True) as descriptor:
        assert process_identity(expected['unit']) == expected, 'Owned application process identity changed'
        signal.pidfd_send_signal(descriptor.fileno(), signal.SIGKILL)


def contained(expected):
    stop_unit(expected['unit'])
    group = Path('/sys/fs/cgroup') / expected['cgroup'].lstrip('/')
    if group.exists():
        events = dict(line.split() for line in (group / 'cgroup.events').read_text().splitlines())
        assert events['populated'] == '0', 'Old application cgroup still owns descendants'
    process = Path('/proc') / str(expected['pid']) / 'stat'
    if process.exists():
        assert process.read_text().rsplit(')', 1)[1].split()[19] != expected['startTicks']


class OwnedApplication:
    def __init__(self, root, unit, arguments, endpoint, deadline, allow_restart=False):
        self.root, self.name, self.arguments = root, unit, arguments
        self.endpoint, self.deadline = endpoint, deadline
        self.units, self.sequence, self.identity = [], 0, None
        self.control = root / 'app-control' if allow_restart else None
        if self.control is not None:
            self.control.mkdir(mode=0o700)

    def start(self):
        remaining = math.floor(self.deadline - time.monotonic())
        assert remaining > 0, 'Original fixture lifetime has expired'
        unit = self.name + ('-r' + str(self.sequence) if self.sequence else '') + '.service'
        self.units.append(unit)
        run(['systemd-run', '--quiet', '--collect', '--unit', unit,
             '-p', 'RuntimeMaxSec=' + str(remaining), *self.arguments])
        deadline = min(self.deadline, time.monotonic() + 90)
        while True:
            observed = process_identity(unit)
            logs = run(['journalctl', '-u', unit, '--no-pager', '-n', '30', '-o', 'cat'])
            if 'Synthetic acceptance services are ready' in logs:
                break
            assert time.monotonic() < deadline, 'Disposable application did not become ready'
            time.sleep(.5)
        with urllib.request.urlopen(self.endpoint + '/actuator/health', timeout=5) as response:
            assert response.status == 200
        assert process_identity(unit) == observed, 'App identity changed during readiness'
        self.identity = observed
        if self.control is not None:
            publish(self.control / ('identity-' + str(self.sequence) + '.json'), observed)
        return observed

    def poll(self):
        request = self.control / ('request-' + str(self.sequence + 1) + '.json') if self.control else None
        if request is None or not request.exists():
            assert process_identity(self.identity['unit']) == self.identity
            return
        value = read_private(request)
        assert set(value) == {'sequence', 'action', 'expected'}
        assert type(value['sequence']) is int and value['sequence'] == self.sequence + 1 and self.sequence < 2
        assert value['action'] == 'restart-app' and value['expected'] == self.identity
        assert self.deadline - time.monotonic() >= 90
        old = self.identity
        publish(self.control / ('claimed-' + str(value['sequence']) + '.json'), value)
        print(json.dumps({'appRestartClaimed': value}), flush=True)
        kill_exact(old)
        contained(old)
        self.sequence += 1
        current = self.start()
        publish(self.control / ('reply-' + str(self.sequence) + '.json'),
                {'sequence': self.sequence, 'old': old, 'current': current, 'appContained': True})
        print(json.dumps({'appRestarted': {'sequence': self.sequence, 'old': old, 'current': current}}), flush=True)

    def close(self):
        failures = []
        for unit in self.units:
            try:
                stop_unit(unit)
            except Exception as error:
                failures.append(type(error).__name__)
        assert not failures, 'Owned application cleanup was not confirmed: ' + ','.join(failures)
