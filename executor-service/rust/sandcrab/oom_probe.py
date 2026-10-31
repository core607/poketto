#!/usr/bin/env python3
"""Owned native parent/service OOM fixtures. Never selects an installed service or existing volume."""
import argparse
import hashlib
from http.client import HTTPException
import json
import os
from pathlib import Path
import shlex
import signal
import subprocess
import time
import uuid

from native_probe import call, http
from oom_evidence import PendingKernelEvidence, increased, kernel_victims, process_topology, reject_global_oom, unit_policy
from volume_probe import VolumeProbe

MIB = 1024 * 1024
POOL_BYTES = 384 * MIB
SERVICE_BYTES = 96 * MIB
LOCAL_BYTES = 32 * MIB
GUEST_BYTES = 256 * MIB
OUTPUT_COUNT = 64
OUTPUT_BYTES = 768 * 1024


def properties(unit):
    result = call('systemctl', 'show', unit, '--no-pager',
                  '--property=MainPID,ControlGroup,OOMPolicy,OOMScoreAdjust,KillMode,Result,ActiveState')
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def process(pid):
    root = Path('/proc') / str(pid)
    stat = (root / 'stat').read_text().rsplit(')', 1)[1].split()
    groups = [line[3:] for line in (root / 'cgroup').read_text().splitlines() if line.startswith('0::')]
    assert len(groups) == 1
    return {'pid': pid, 'startTicks': int(stat[19]), 'exe': Path(os.readlink(root / 'exe')).name,
            'cgroup': groups[0], 'oomScoreAdjust': int((root / 'oom_score_adj').read_text())}


def wait_until(predicate, message, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.05)
    raise AssertionError(message)


def counters(group, local=True):
    return {key: int(value) for key, value in
            (line.split() for line in (group / ('memory.events.local' if local else 'memory.events')).read_text().splitlines())}


def python(source):
    return 'python3 -c ' + shlex.quote(source)


def kernel_window(cursor):
    journal = call('journalctl', '-k', '--after-cursor', cursor, '--no-pager', '-o', 'cat', '-n', '4096').stdout
    assert len(journal) <= 2 * MIB and len(journal.splitlines()) < 4096, 'Kernel window exceeded its evidence bound'
    return journal


class OomProbe(VolumeProbe):
    def __init__(self, binary, tools, report):
        super().__init__(binary, tools, report)
        self.service_bytes = SERVICE_BYTES
        self.policy = 'stop'
        self.volumes = []
        self.sessions = []
        source = self.config.read_text()
        replacements = {'max_records = 8\n': 'max_records = 128\n',
                        'max_output_bytes = 1048576\n': 'max_output_bytes = 67108864\n',
                        'memory_bytes = 67108864\n': 'memory_bytes = 268435456\n',
                        'session_ttl_millis = 60000\n': 'session_ttl_millis = 120000\n',
                        'output_limit_bytes = 4096\n': 'output_limit_bytes = 1048576\n'}
        for before, after in replacements.items():
            assert source.count(before) == (2 if before.startswith(('memory_', 'session_', 'output_')) else 1)
            source = source.replace(before, after)
        self.config.write_text(source)
        self.receipt.update({'scope': 'Disposable native service and XFS volumes; kernel/cgroup OOM evidence, no application or production data',
                             'oomProbeSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                             'bounds': {'poolMemoryBytes': POOL_BYTES, 'poolCpuPercent': 50, 'poolTasks': 256,
                                        'normalServiceMemoryBytes': SERVICE_BYTES, 'localServiceMemoryBytes': LOCAL_BYTES,
                                        'guestMemoryBytes': GUEST_BYTES, 'swapBytes': 0, 'diskBytes': 512 * MIB},
                             'fixedAttempts': {'parent': 1, 'daemon': 1, 'memberContinue': 1, 'memberStop': 1},
                             'attempts': []})
        assert call('systemctl', 'show', self.unit, '-p', 'LoadState', '--value').stdout.strip() == 'not-found'
        self.unit_file = Path('/run/systemd/system') / self.unit
        self.unit_definition = b''
        with self.unit_file.open('xb'):
            pass
        self.unit_file.chmod(0o644)
        self.unit_identity = self.unit_file.stat()

    def start(self):
        state = properties(self.unit)
        assert state['MainPID'] == '0' and state['ActiveState'] in ('inactive', 'failed'), 'Stop the owned service before changing its phase'
        self.require_unit_owner()
        definition = (f'[Unit]\nDescription=Disposable native OOM fixture\n[Service]\nType=simple\nUser=root\n'
                      f'Slice={self.slice}\nExecStart={self.binary} serve --config {self.config}\n'
                      f'MemoryMax={self.service_bytes}\nMemorySwapMax=0\nTasksMax=128\n'
                      f'OOMScoreAdjust=-900\nOOMPolicy={self.policy}\nRestart=no\nKillMode=control-group\n'
                      'TimeoutStopSec=10\nEnvironment=PATH=/usr/bin:/bin\n').encode()
        pending = self.unit_file.with_suffix('.service.pending')
        with pending.open('xb') as output:
            output.write(definition)
            output.flush()
            os.fsync(output.fileno())
        pending.chmod(0o644)
        os.replace(pending, self.unit_file)
        self.unit_definition = definition
        self.unit_identity = self.unit_file.stat()
        call('systemctl', 'daemon-reload')
        call('systemctl', 'start', self.unit)
        self.wait_ready()
        self.guard()

    def require_unit_owner(self):
        assert not self.unit_file.is_symlink(), 'Fixture unit was replaced with a symlink'
        current = self.unit_file.stat()
        assert (current.st_dev, current.st_ino) == (self.unit_identity.st_dev, self.unit_identity.st_ino), 'Fixture unit ownership changed'
        assert self.unit_file.read_bytes() == self.unit_definition, 'Fixture unit definition changed outside this controller'

    def cleanup(self):
        super().cleanup()
        self.require_unit_owner()
        self.unit_file.unlink()
        call('systemctl', 'daemon-reload')

    def save(self):
        self.report.parent.mkdir(parents=True, exist_ok=True)
        pending = self.report.with_suffix('.pending')
        with pending.open('x') as output:
            json.dump(self.receipt, output, indent=2)
            output.write('\n')
            output.flush()
            os.fsync(output.fileno())
        os.replace(pending, self.report)
        directory = os.open(self.report.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            os.fsync(directory)
        finally:
            os.close(directory)

    def guard(self):
        pool_name = call('systemctl', 'show', self.slice, '-p', 'ControlGroup', '--value').stdout.strip()
        assert pool_name.endswith('/' + self.slice) and pool_name != '/' + self.unit
        self.pool_group = Path('/sys/fs/cgroup') / pool_name.lstrip('/')
        state = properties(self.unit)
        self.service_group = Path('/sys/fs/cgroup') / state['ControlGroup'].lstrip('/')
        assert self.service_group.parent == self.pool_group and self.service_group.name == self.unit
        for group, memory, tasks in ((self.pool_group, POOL_BYTES, 256),
                                     (self.service_group, self.service_bytes, 128)):
            for name, expected in (('memory.max', memory), ('memory.swap.max', 0), ('pids.max', tasks)):
                assert int((group / name).read_text()) == expected, (name, expected)
            assert (group / 'memory.oom.group').read_text().strip() == '0'
        quota, period = map(int, (self.pool_group / 'cpu.max').read_text().split())
        assert quota * 100 == period * 50
        main = process(int(state['MainPID']))
        unit_policy(state, str(main['oomScoreAdjust']), policy=self.policy)
        assert main['cgroup'] == state['ControlGroup']
        available = next(int(line.split()[1]) * 1024 for line in Path('/proc/meminfo').read_text().splitlines()
                         if line.startswith('MemAvailable:'))
        assert available >= 1024 * MIB, 'Insufficient host headroom for this fixed owned fixture'
        return main

    def begin(self, name, group):
        assert not any(item['name'] == name for item in self.receipt['attempts']), 'Never retry an OOM case'
        main = self.guard()
        cursor = call('journalctl', '-k', '-n', '0', '--show-cursor', '--no-pager').stdout
        cursor = next(line.removeprefix('-- cursor: ') for line in cursor.splitlines() if line.startswith('-- cursor: '))
        item = {'name': name, 'main': main, 'epoch': self.info['serverId'],
                'cgroup': '/' + group.relative_to('/sys/fs/cgroup').as_posix(),
                'before': counters(group), 'poolBefore': counters(self.pool_group, local=False),
                'cursor': cursor, 'result': 'RUNNING'}
        self.receipt['attempts'].append(item)
        self.save()
        return item

    def evidence(self, item, group, *, required=None, forbidden=None):
        try:
            after = counters(group)
        except FileNotFoundError:
            after = None
        pool_after = counters(self.pool_group, local=False)
        item.update({'after': after, 'poolAfter': pool_after})
        self.save()
        increased(item['poolBefore'], pool_after, item['before'], after)
        victims = self.kernel_evidence(item, required, forbidden)
        item.update({'victims': victims, 'kernelResult': 'PASS'})
        self.save()

    def kernel_evidence(self, item, required, forbidden):
        deadline = time.monotonic() + 5
        while True:
            journal = kernel_window(item['cursor'])
            item['kernelOom'] = [line for line in journal.splitlines() if 'oom-kill:' in line]
            self.save()
            try:
                return kernel_victims(journal, item['cgroup'], required_pid=required, forbidden_pid=forbidden)
            except PendingKernelEvidence:
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.05)  # Observe only this cursor and attempt; do not repeat pressure.

    def complete(self, item, label):
        item['result'] = 'PASS'
        self.done(label)

    def command(self, session, source):
        request, result = self.session_exec(session, source)
        assert result['state'] == 'succeeded', result
        self.forget(request)
        return result['result']['stdout']

    def preserve(self):
        for _ in range(2):
            volume, result = self.volume()
            assert result['state'] == 'ready'
            self.volumes.append(volume)
            session = self.session_open(volumeId=volume['id'])
            self.sessions.append(session)
            self.command(session, python('import os\nfrom pathlib import Path\n'
                'for name,data in [("draft",b"retained draft"),("binary",bytes(range(256)))]:\n'
                ' with open(name,"wb") as f: f.write(data); f.flush(); os.fsync(f.fileno())\n'))

    def readbacks(self):
        self.sessions = []
        for volume in self.volumes:
            session = self.session_open(volumeId=volume['id'])
            self.sessions.append(session)
            assert self.command(session, python('from pathlib import Path\n'
                'assert Path("draft").read_bytes()==b"retained draft"\n'
                'assert Path("binary").read_bytes()==bytes(range(256))\nprint("retained")')) == 'retained\n'

    def topology(self):
        main = self.guard()
        members = [process(int(pid)) for pid in (self.service_group / 'cgroup.procs').read_text().split()]
        relays = [member for member in members if member['exe'] == 'systemd-run']
        guests = []
        for path in self.pool_group.rglob('cgroup.procs'):
            if path.parent == self.service_group:
                continue
            for pid in path.read_text().split():
                guests.append(process(int(pid)))
        process_topology(main, relays, guests, main['cgroup'], '/' + self.pool_group.relative_to('/sys/fs/cgroup').as_posix())
        self.receipt['topology'] = {'main': main, 'relays': relays, 'independentGuests': guests}
        self.done('native-supervision-and-relay-score-inheritance')

    def parent_oom(self):
        item = self.begin('parent', self.pool_group)
        source = python('import time\nfrom pathlib import Path\n'
                        'x=bytearray(210*1024*1024)\n'
                        'for n in range(0,len(x),4096): x[n]=1\n'
                        'Path("oom-ready").write_text("ready")\ntime.sleep(45)')
        self.command(self.sessions[0], source + ' </dev/null >/dev/null 2>&1 &')
        ready = self.data / 'volumes' / self.volumes[0]['id'] / 'data/oom-ready'
        wait_until(ready.exists, 'First bounded allocator did not become ready')
        self.session_exec(self.sessions[1], source + ' </dev/null >/dev/null 2>&1 &', wait=False)
        wait_until(lambda: counters(self.pool_group, local=False)['oom_kill'] > item['poolBefore']['oom_kill'], 'No parent OOM')
        self.evidence(item, self.pool_group, forbidden=item['main']['pid'])
        assert self.guard() == item['main']
        assert http(self.socket, 'GET', '/v1/info')[1]['serverId'] == item['epoch']
        for session in self.sessions:
            self.session_close(session)
            self.session_forget(session)
        self.readbacks()
        self.complete(item, 'native-parent-pool-oom-retains-main-epoch-and-both-volumes')

    def contained(self):
        wait_until(lambda: properties(self.unit)['MainPID'] == '0', 'Service did not stop after its member OOM')
        assert properties(self.unit)['Result'] == 'oom-kill'
        def empty():
            return all(not file.read_text().strip() for file in self.pool_group.rglob('cgroup.procs'))
        wait_until(empty, 'Owned service or sandbox descendants remain')

    def recover(self, previous):
        self.restart()
        assert self.info['serverId'] != previous
        assert not list((self.socket.parent / 'jobs').iterdir()), 'New epoch published before old workspaces recovered'
        self.readbacks()

    def daemon_oom(self):
        previous = self.info['serverId']
        self.service_bytes = LOCAL_BYTES
        self.recover(previous)
        item = self.begin('daemon', self.service_group)
        source = python('import os\nblock=b"x"*16384\nfor _ in range(48): os.write(1,block)')
        item['pressure'] = {'maximumCommands': OUTPUT_COUNT, 'bytesPerCommand': OUTPUT_BYTES,
                            'guestChunkBytes': 16384, 'completed': 0, 'submittedIds': []}
        self.save()
        for _ in range(OUTPUT_COUNT):
            try:
                output = self.retained_output(source, item)
                assert len(output) == OUTPUT_BYTES
                item['pressure']['completed'] += 1
                self.save()
            except (OSError, HTTPException):
                break  # Observe this service identity; never replay an uncertain submission.
        self.contained()
        self.evidence(item, self.service_group, required=item['main']['pid'])
        self.recover(item['epoch'])
        self.complete(item, 'native-daemon-local-oom-contains-processes-and-recovers-volumes')

    def retained_output(self, source, item):
        request = {'id': str(uuid.uuid4()), 'serverId': self.info['serverId'], 'command': source}
        item['pressure']['submittedIds'].append(request['id'])
        self.save()
        status, reply = http(self.socket, 'POST', '/v1/sessions/' + self.sessions[0]['id'] + '/commands', request)
        assert status == 202, reply
        result = self.wait(request)
        assert result['state'] == 'succeeded', result
        return result['result']['stdout']

    def member_oom(self, policy):
        previous = self.info['serverId']
        self.policy = policy
        self.recover(previous)
        item = self.begin('memberContinue' if policy == 'continue' else 'memberStop', self.service_group)
        source = ('import os,time\nfrom pathlib import Path\n'
                  'Path("/proc/self/oom_score_adj").write_text("0")\n'
                  f'Path({str(self.service_group / "cgroup.procs")!r}).write_text(str(os.getpid()))\n'
                  'x=bytearray(64*1024*1024)\n'
                  'for n in range(0,len(x),4096): x[n]=1\ntime.sleep(10)')
        # A disposable fault member exercises the real service-member policy. It is not a file/volume worker.
        helper = subprocess.Popen(['/usr/bin/python3', '-c', source], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        item['faultMemberPid'] = helper.pid
        self.save()
        try:
            assert helper.wait(timeout=20) == -signal.SIGKILL
        finally:
            if helper.poll() is None:
                helper.kill()
                helper.wait(timeout=5)
        if policy == 'continue':
            time.sleep(1)
            assert self.guard() == item['main']
            assert http(self.socket, 'GET', '/v1/info')[1]['serverId'] == item['epoch']
            self.evidence(item, self.service_group, required=helper.pid, forbidden=item['main']['pid'])
            self.complete(item, 'native-service-member-oom-continue-negative-control')
        else:
            self.contained()
            self.evidence(item, self.service_group, required=helper.pid, forbidden=item['main']['pid'])
            self.service_bytes = SERVICE_BYTES
            self.recover(item['epoch'])
            self.complete(item, 'native-service-member-oom-stop-policy-recovers-both-volumes')

    def tests(self):
        self.preserve()
        self.topology()
        self.parent_oom()
        self.daemon_oom()
        self.member_oom('continue')
        self.member_oom('stop')
        for session in self.sessions:
            self.session_close(session)
            self.session_forget(session)
        for volume in self.volumes:
            self.volume_delete(volume)
            self.volume_forget(volume)
        journal = kernel_window(self.receipt['attempts'][0]['cursor'])
        self.receipt['wholeExerciseKernelOom'] = [line for line in journal.splitlines() if 'oom-kill:' in line]
        self.save()
        reject_global_oom(journal)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0, 'Only a root operator may create this disposable fixture'
    assert not args.report.exists(), 'Preserve earlier attempts'
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Native OOM probe interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    signal.alarm(300)
    probe = OomProbe(args.binary.resolve(), args.tools.resolve(), args.report.resolve())
    try:
        probe.setup()
        probe.tests()
        probe.receipt['result'] = 'PASS'
    except BaseException as failure:
        probe.receipt['result'] = 'FAIL'
        probe.receipt['failureType'] = type(failure).__name__
        probe.receipt['failureMessage'] = str(failure)[:2048]
        for item in probe.receipt['attempts']:
            if item['result'] == 'RUNNING':
                item['result'] = 'FAIL'
        raise
    finally:
        signal.alarm(0)
        try:
            probe.cleanup()
        except BaseException as failure:
            probe.receipt.update({'result': 'FAIL', 'cleanup': 'FAIL', 'cleanupFailure': type(failure).__name__,
                                  'cleanupMessage': str(failure)[:2048]})
            raise
        finally:
            probe.save()


if __name__ == '__main__':
    main()
