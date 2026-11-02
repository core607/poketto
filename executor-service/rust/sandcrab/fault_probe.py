#!/usr/bin/env python3
"""Privileged acceptance controller: stop real launches at kernel exec/exit events.

Only the named disposable daemon and descendants are traced. This file is not
installed with Sandcrab and supplies no runtime hook or alternate launch path.
"""
import argparse
import ctypes
import errno
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import time

CONT, DETACH, GETEVENTMSG = 7, 17, 0x4201
SEIZE, INTERRUPT = 0x4206, 0x4207
OPTIONS = 2 | 4 | 8 | 16 | 64  # fork, vfork, clone, exec, exit
WAIT_ALL = 0x40000000
LIBC = ctypes.CDLL(None, use_errno=True)
LIBC.ptrace.restype = ctypes.c_long
LIBC.ptrace.argtypes = [ctypes.c_uint, ctypes.c_int, ctypes.c_void_p, ctypes.c_void_p]


def trace(operation, pid, data=0):
    value = data if not isinstance(data, int) else ctypes.c_void_p(data)
    if LIBC.ptrace(operation, pid, None, value) == -1:
        code = ctypes.get_errno()
        raise OSError(code, os.strerror(code))


def message(pid):
    value = ctypes.c_ulong()
    trace(GETEVENTMSG, pid, ctypes.byref(value))
    return value.value


class LaunchFaults:
    def __init__(self, binary, runtime, directory):
        self.binary, self.runtime, self.directory = binary, runtime, directory
        self.gate, self.entered = directory / 'gate.json', directory / 'entered'
        self.process = None
        self.log = None
        self.pid = None

    def start(self, pid):
        if self.pid == pid and self.process is not None and self.process.poll() is None:
            return
        self.close()
        self.pid = pid
        self.log = (self.directory / f'trace-{pid}.log').open('x')
        self.process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--pid', str(pid),
            '--binary', str(self.binary), '--runtime', str(self.runtime), '--directory', str(self.directory)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.log, start_new_session=True)
        with selectors.DefaultSelector() as selector:
            selector.register(self.process.stdout, selectors.EVENT_READ)
            if not selector.select(10) or self.process.stdout.readline() != b'READY\n':
                raise AssertionError('Kernel launch fault controller did not attach')

    def arm(self, kind, phase, action=None):
        assert self.process is not None and self.process.poll() is None
        self.entered.unlink(missing_ok=True)
        temporary = self.gate.with_suffix('.pending')
        temporary.write_text(json.dumps({'kind': kind, 'phase': phase, 'action': action}))
        temporary.replace(self.gate)

    def wait(self, timeout=10):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            assert self.process.poll() is None, 'Kernel launch fault controller exited'
            if self.entered.exists():
                return json.loads(self.entered.read_text())
            time.sleep(.01)
        raise AssertionError('Real launcher did not reach the controlled kernel boundary')

    def release(self):
        self.gate.unlink(missing_ok=True)

    def close(self):
        if self.process is not None:
            if self.process.poll() is None:
                self.process.stdin.close()
                try:
                    self.process.wait(timeout=8)
                except subprocess.TimeoutExpired:
                    self.process.kill()
                    self.process.wait(timeout=5)
                    raise AssertionError('Kernel fault controller could not detach')
            assert self.process.returncode == 0, 'Kernel fault controller failed; inspect its retained log'
            self.process.stdout.close()
            self.process = None
        if self.log is not None:
            self.log.close()
            self.log = None


class Controller:
    def __init__(self, args):
        self.args = args
        self.traced, self.held, self.selected = set(), set(), {}
        self.gate, self.entered = args.directory / 'gate.json', args.directory / 'entered'
        self.stopping = False

    def publish(self, value):
        temporary = self.entered.with_suffix('.pending')
        temporary.write_text(json.dumps(value))
        temporary.replace(self.entered)

    def detach(self, pid):
        trace(DETACH, pid)
        self.traced.discard(pid)
        self.held.discard(pid)
        self.selected.pop(pid, None)

    def selected_launch(self, argv):
        if len(argv) != 3 or argv[0] != str(self.args.binary) or not self.gate.exists():
            return None
        fault = json.loads(self.gate.read_text())
        path = Path(argv[2])
        if argv[1] == '__launch' and fault['kind'] == 'launch':
            assert path.parent == self.args.runtime / 'records'
            record = json.loads(path.read_text())
        elif argv[1] == '__helper' and fault['kind'] in ('file', 'copy'):
            assert path.parent.parent == self.args.runtime / 'helper-jobs'
            record = json.loads(path.read_text())
            if not path.parent.name.startswith(fault['kind'] + '-'):
                return None
            if fault['kind'] == 'file':
                operation = Path(record['argv'][2])
                assert operation.parent == self.args.runtime / 'file-ops'
                job = json.loads(operation.read_text())
                if job['action'] != fault['action']:
                    return None
                record['operation'] = str(operation)
        else:
            return None
        group = Path(record['cgroup'])
        assert group.is_relative_to('/sys/fs/cgroup') and group.name.startswith(('run-', 'file-', 'copy-'))
        return {'fault': fault, 'record': record, 'leaf': False}

    def event(self, pid, status):
        if os.WIFEXITED(status) or os.WIFSIGNALED(status):
            self.traced.discard(pid)
            self.held.discard(pid)
            self.selected.pop(pid, None)
            return
        assert os.WIFSTOPPED(status)
        event = status >> 16
        if event in (1, 2, 3):
            child = message(pid)
            self.traced.add(child)
            if pid in self.selected:
                self.selected[child] = {**self.selected[pid], 'leaf': False}
        if self.stopping:
            self.detach(pid)
            return
        if event == 4:
            argv = [part.decode() for part in (Path('/proc') / str(pid) / 'cmdline').read_bytes().split(b'\0') if part]
            selected = self.selected.get(pid) or self.selected_launch(argv)
            if selected is None:
                self.detach(pid)
                return
            self.selected[pid] = selected
            phase = selected['fault']['phase']
            if argv[1:2] in (['__launch'], ['__helper']):
                group = Path(selected['record']['cgroup'])
                if phase == 'before':
                    self.held.add(pid)
                    self.publish({'pid': pid, 'phase': phase, 'cgroup': str(group),
                                  'populated': (group / 'cgroup.events').read_text(),
                                  'membership': (Path('/proc') / str(pid) / 'cgroup').read_text()})
                    return
                if phase == 'fail':
                    print(f'inject SIGKILL before placement: pid={pid}', file=sys.stderr, flush=True)
                    os.kill(pid, signal.SIGKILL)
                if phase == 'oom':
                    (group / 'memory.max').write_text('8388608')
                    assert (group / 'memory.max').read_text().strip() == '8388608'
            elif argv[0] == str(self.args.binary) and argv[1:2] in (['__file'], ['__copy-tree']):
                selected['leaf'] = True
            elif Path(argv[0]).name == 'bwrap' and '--version' in argv:
                self.detach(pid)
                return
        if event == 6 and pid in self.selected:
            selected = self.selected[pid]
            phase = selected['fault']['phase']
            if phase == 'oom':
                group = Path(selected['record']['cgroup'])
                values = dict(line.split() for line in (group / 'memory.events').read_text().splitlines())
                if int(values['oom_kill']) > 0:
                    self.publish({'pid': pid, 'phase': phase, 'oomKills': int(values['oom_kill'])})
            if selected['leaf'] and message(pid) == 0:
                if phase == 'after':
                    self.held.add(pid)
                    self.publish({'pid': pid, 'phase': phase, 'exitStatus': 0})
                    return
                if phase == 'drop-record':
                    Path(selected['record']['operation']).unlink()
                    self.gate.unlink()
        deliver = os.WSTOPSIG(status) if event == 0 else 0
        trace(CONT, pid, deliver)

    def run(self):
        for path in (Path('/proc') / str(self.args.pid) / 'task').iterdir():
            pid = int(path.name)
            try:
                trace(SEIZE, pid, OPTIONS)
                self.traced.add(pid)
            except OSError as error:
                if error.errno != errno.ESRCH:
                    raise
        assert self.traced
        print('READY', flush=True)
        with selectors.DefaultSelector() as selector:
            selector.register(sys.stdin, selectors.EVENT_READ)
            try:
                while self.traced:
                    if selector.select(0):
                        assert not os.read(sys.stdin.fileno(), 1)
                        break
                    if self.held and not self.gate.exists():
                        for pid in list(self.held):
                            self.detach(pid)
                    for pid in list(self.traced):
                        try:
                            observed, status = os.waitpid(pid, os.WNOHANG | WAIT_ALL)
                            if observed:
                                self.event(pid, status)
                        except ChildProcessError:
                            self.traced.discard(pid)
                            self.held.discard(pid)
                            self.selected.pop(pid, None)
                        except ProcessLookupError:
                            # ESRCH from ptrace can mean a killed tracee is
                            # entering its exit stop, not that it was reaped.
                            # Keep waiting so its actual parent can observe exit.
                            pass
                    time.sleep(.001)
            finally:
                self.stopping = True
                for pid in list(self.held):
                    try:
                        self.detach(pid)
                    except ProcessLookupError:
                        pass
                for pid in list(self.traced):
                    try:
                        trace(INTERRUPT, pid)
                    except ProcessLookupError:
                        pass
                deadline = time.monotonic() + 5
                while self.traced and time.monotonic() < deadline:
                    for pid in list(self.traced):
                        try:
                            observed, status = os.waitpid(pid, os.WNOHANG | WAIT_ALL)
                            if observed:
                                self.event(pid, status)
                        except ChildProcessError:
                            self.traced.discard(pid)
                        except ProcessLookupError:
                            pass
                    time.sleep(.001)
                assert not self.traced, 'Fixture tracees did not detach'


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--pid', type=int, required=True)
    for option in ('binary', 'runtime', 'directory'):
        parser.add_argument('--' + option, type=Path, required=True)
    arguments = parser.parse_args()
    assert os.geteuid() == 0
    assert Path(f'/proc/{arguments.pid}/exe').resolve() == arguments.binary.resolve()
    assert arguments.binary.parent.name.startswith('sandcrab-native-')
    Controller(arguments).run()
