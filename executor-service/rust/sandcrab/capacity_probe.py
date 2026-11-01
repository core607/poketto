#!/usr/bin/env python3
"""Bounded native admission above 64 idle sessions; not a workload throughput benchmark."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import uuid

from native_probe import Probe, call, http


class CapacityProbe(Probe):
    SUPERVISOR_MEMORY = '256M'
    SUPERVISOR_TASKS = 768
    POOL_MEMORY = '1G'
    POOL_TASKS = 2048

    def __init__(self, binary, tools, report):
        super().__init__(binary, tools, report)
        self.config.write_text(self.config.read_text()
            .replace('max_sandboxes = 2', 'max_sandboxes = 65')
            .replace('max_records = 8', 'max_records = 128')
            .replace('memory_bytes = 67108864', 'memory_bytes = 16777216')
            .replace('tasks_max = 64', 'tasks_max = 16')
            .replace('session_ttl_millis = 60000', 'session_ttl_millis = 300000'))
        self.receipt.update(scope='65 idle native sessions with independent live cgroup checks, shared run/session admission and cleanup; no workload or production-capacity claim',
                            capacityProbeSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                            result='NOT_RUN')
        self.sessions = []

    def tests(self):
        assert self.info['limits']['maxSandboxes'] == 65
        for capacity, accepted in [(224, True), (256, True), (257, False)]:
            config = self.root / f'capacity-{capacity}.toml'
            config.write_text(self.config.read_text().replace('max_sandboxes = 65', f'max_sandboxes = {capacity}')
                              .replace('max_records = 128', 'max_records = 1024'))
            config.chmod(0o600)
            result = call(str(self.binary), 'check', '--config', str(config), check=False)
            assert (result.returncode == 0) == accepted, result
            if accepted:
                assert json.loads(result.stdout) == {'configurationValid': True}
            else:
                assert '1..256 max_sandboxes' in result.stderr
        self.done('operator-capacity-validation-reaches-256-and-keeps-its-upper-bound')

        sessions = self.sessions
        for index in range(65):
            session = self.session_open(wait=False)
            sessions.append(session)
            observed = self.session_wait(session)
            assert observed['state'] == 'ready', observed
            self.receipt['readySessions'] = index + 1
            if (index + 1) % 16 == 0 or index == 64:
                self.save()
                print(f'capacity: {index + 1} sessions ready', flush=True)
        groups = []
        for session in sessions:
            assert http(self.socket, 'GET', '/v1/sessions/' + session['id'])[1]['state'] == 'ready'
            unit = self.name + '-' + session['id'] + '.service'
            state = dict(line.split('=', 1) for line in call('systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'ControlGroup').stdout.splitlines() if '=' in line)
            assert state['ActiveState'] == 'active' and state['ControlGroup'].startswith('/')
            group = Path('/sys/fs/cgroup') / state['ControlGroup'].lstrip('/')
            assert (group / 'cgroup.procs').read_text().strip()
            assert int((group / 'memory.max').read_text()) == 16777216
            assert int((group / 'pids.max').read_text()) == 16
            groups.append(group)
        assert len(set(groups)) == 65
        self.receipt['observedLiveSessions'] = len(groups)
        self.done('65-ready-sessions-have-distinct-live-bounded-kernel-cgroups')

        for kind, code in [('sessions', 'SESSION_CAPACITY'), ('runs', 'RUN_CAPACITY')]:
            identifier = str(uuid.uuid4())
            request = {'id': identifier, 'serverId': self.info['serverId'], 'profile': 'python'}
            if kind == 'runs':
                request['command'] = ['true']
            status, value = http(self.socket, 'POST', '/v1/' + kind, request)
            assert status == 429 and value['error']['code'] == code, value
            assert http(self.socket, 'GET', '/v1/' + kind + '/' + identifier)[0] == 404
        self.done('configured-live-cap-refuses-both-entrances-without-retaining-a-request')

        first = sessions.pop(0)
        self.session_close(first)
        self.session_forget(first)
        sessions.append(self.session_open())
        self.done('contained-session-releases-exactly-one-admission-slot')
        for session in sessions:
            self.session_close(session)
            self.session_forget(session)
        for group in groups:
            assert not any(path.read_text().strip() for path in group.rglob('cgroup.procs'))
        for kind in ('sessions', 'runs'):
            status, listed = http(self.socket, 'GET', '/v1/' + kind)
            assert status == 200 and listed['ids'] == [] and listed['nextAfter'] is None
        self.done('capacity-probe-releases-every-session-and-retained-record')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('binary', 'tools', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0 and not args.report.exists()

    def interrupted(signum, frame):
        raise TimeoutError('Capacity work or cleanup exceeded its deadline')

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    probe = CapacityProbe(args.binary.resolve(), args.tools.resolve(), args.report.resolve())
    probe.save()
    try:
        signal.alarm(180)
        probe.setup()
        probe.tests()
        probe.receipt['result'] = 'PASS'
    except BaseException as error:
        probe.receipt.update(result='FAIL', failureType=type(error).__name__)
        units = [probe.unit, probe.slice]
        if probe.sessions:
            units.append(probe.name + '-' + probe.sessions[-1]['id'] + '.service')
        diagnostic = str(error)
        for unit in units:
            diagnostic += '\n' + call('systemctl', 'show', unit, '-p', 'ActiveState', '-p', 'Result',
                                       '-p', 'MemoryPeak', '-p', 'TasksCurrent', check=False).stdout
            diagnostic += '\n' + call('journalctl', '-u', unit, '--no-pager', '-n', '30', check=False).stdout
        args.report.with_suffix('.diagnostic.txt').write_text(diagnostic)
        raise
    finally:
        signal.alarm(90)
        try:
            probe.cleanup()
        except BaseException:
            probe.receipt.update(result='FAIL', cleanup='UNCONFIRMED')
            raise
        finally:
            signal.alarm(0)
            probe.save()


if __name__ == '__main__':
    main()
