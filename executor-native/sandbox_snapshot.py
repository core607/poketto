#!/usr/bin/env python3
"""Runs inside either launcher; only synthetic fixture paths are supplied by the root controller."""
import ctypes
import errno
import json
import os
from pathlib import Path
import socket
import sys

lease, tools, canary = map(Path, sys.argv[1:])
assertions = []
mounts = Path('/proc/self/mountinfo').read_text().splitlines()
mount_flags = {line.split()[4]: set(line.split()[5].split(',')) for line in mounts}


def check(name, condition):
    assert condition, name
    assertions.append(name)


for path in (Path('/etc/passwd'), Path('/root'), Path('/home'), Path('/srv'), canary, lease.parent):
    try:
        if path.is_dir():
            entries = list(path.iterdir())
            # The empty-root scaffolding can name this lease, but exposes no sibling or host files.
            readable = bool(entries) and not (path == lease.parent and entries == [lease])
        else:
            path.read_bytes()
            readable = True
    except OSError:
        readable = False
    check('host-read-denied:' + str(path), not readable)
check('host-sysfs-hidden', not Path('/sys').exists() or not list(Path('/sys').iterdir()))
for path in (tools, lease / 'bootstrap', lease / 'bridge/responses', Path('/usr'), Path('/')):
    check('read-mount-readonly:' + str(path), 'ro' in mount_flags[str(path)])
    target = path / 'probe-write' if path.is_dir() else path
    try:
        with target.open('ab'):
            pass
        writable = True
    except OSError:
        writable = False
    check('read-grant-not-writable:' + str(path), not writable)
for path in (lease / 'work', lease / 'home', lease / 'bridge/requests', Path('/tmp')):
    check('write-mount-writable:' + str(path), 'rw' in mount_flags[str(path)])
    target = path / 'sandbox-probe-write'
    target.write_text('fixture')
    check('write-grant:' + str(path), target.read_text() == 'fixture')
    target.unlink()
try:
    with socket.socket(socket.AF_UNIX):
        pass
    blocked = False
except OSError as error:
    blocked = error.errno == errno.EPERM
check('unix-socket-blocked', blocked)
libc = ctypes.CDLL(None, use_errno=True)
for number in (425, 426, 427):
    ctypes.set_errno(0)
    check('io-uring-blocked:' + str(number),
          libc.syscall(number, 0, 0, 0, 0, 0, 0) == -1 and ctypes.get_errno() == errno.EPERM)
for host in ('1.1.1.1', '127.0.0.1'):
    try:
        with socket.create_connection((host, 80), timeout=0.2):
            pass
        blocked = False
    except OSError:
        blocked = True
    check('network-blocked:' + host, blocked)
status = dict(line.split(':', 1) for line in Path('/proc/self/status').read_text().splitlines() if ':' in line)
security = {key: value.strip() for key, value in status.items()
            if key.startswith('Cap') or key in ('NoNewPrivs', 'Seccomp')}
check('capabilities-empty', int(security['CapEff'], 16) == 0)
check('no-new-privileges', security['NoNewPrivs'] == '1')
check('seccomp-active', security['Seccomp'] == '2')
check('unprivileged-uid', os.geteuid() != 0)
processes = []
for process in Path('/proc').iterdir():
    if process.name.isdigit():
        try:
            processes.append({'pid': int(process.name), 'comm': (process / 'comm').read_text().strip()})
        except OSError:
            pass
check('outer-processes-hidden', not any(p['comm'] in ('bwrap', 'node', 'socat', 'systemd') for p in processes))
ctypes.set_errno(0)
check('pid-one-trace-denied', libc.ptrace(16, 1, 0, 0) == -1 and ctypes.get_errno() == errno.EPERM)
print(json.dumps({'assertions': assertions, 'security': security, 'processes': processes,
                  'environmentNames': sorted(os.environ), 'temporaryDirectory': os.environ['TMPDIR'],
                  'mounts': mounts}))
