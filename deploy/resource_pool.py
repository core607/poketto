"""Read-only verification of the executor's systemd resource pool."""
import argparse
from pathlib import Path
import re
import socket
import struct
import subprocess


class PoolUnavailable(RuntimeError):
    pass


def slice_name(value):
    if (not isinstance(value, str) or len(value) > 240
            or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*\.slice', value)
            or value in ('system.slice', 'user.slice', 'machine.slice')):
        raise PoolUnavailable('A dedicated resourceSlice is required')
    return value


def properties(unit):
    result = subprocess.run(
        ['systemctl', 'show', unit, '--no-pager', '--property=ActiveState,Slice,ControlGroup,MainPID,User,Delegate'],
        capture_output=True, text=True, timeout=5)
    if result.returncode or len(result.stdout) > 8192:
        raise PoolUnavailable('Cannot inspect the installed resource pool')
    return dict(line.split('=', 1) for line in result.stdout.splitlines() if '=' in line)


def group_path(value):
    if (not isinstance(value, str) or not value.startswith('/') or value == '/'
            or not re.fullmatch(r'/[A-Za-z0-9_./@-]+', value)
            or any(part in ('', '.', '..') for part in value.split('/')[1:])):
        raise PoolUnavailable('Invalid resource pool cgroup')
    return value


def process_group(pid='self'):
    lines = (Path('/proc') / str(pid) / 'cgroup').read_text().splitlines()
    values = [line[3:] for line in lines if line.startswith('0::')]
    if len(values) != 1:
        raise PoolUnavailable('Unified cgroup v2 is required')
    return group_path(values[0])


def limits(directory):
    """The kernel files, not repeated application numbers, own the effective budget."""
    def number(name, minimum):
        value = (directory / name).read_text().strip()
        if not re.fullmatch(r'[0-9]{1,20}', value) or not minimum <= int(value) < 2**63:
            raise PoolUnavailable('The resource pool requires finite memory, swap, task and CPU limits')
        return int(value)
    result = {name: number(name, minimum) for name, minimum in
              [('memory.max', 1), ('memory.swap.max', 0), ('pids.max', 1)]}
    cpu = (directory / 'cpu.max').read_text().split()
    if (len(cpu) != 2 or any(not re.fullmatch(r'[0-9]{1,20}', value) for value in cpu)
            or any(not 0 < int(value) < 2**63 for value in cpu)):
        raise PoolUnavailable('The resource pool requires a finite CPU quota')
    result['cpu.max'] = tuple(map(int, cpu))
    return result


class ResourcePool:
    def __init__(self, name):
        self.name = slice_name(name)
        configured = properties(self.name)
        if configured.get('ActiveState') != 'active':
            raise PoolUnavailable('The resource slice must be active')
        self.cgroup = group_path(configured.get('ControlGroup'))
        if self.cgroup.rsplit('/', 1)[1] != self.name:
            raise PoolUnavailable('The slice does not own its reported cgroup')
        # A hyphenated slice has implicit parents. Use systemd's actual path.
        self.directory = Path('/sys/fs/cgroup') / self.cgroup.lstrip('/')

    def verify(self, current=None):
        current = process_group() if current is None else group_path(current)
        if not current.startswith(self.cgroup + '/'):
            raise PoolUnavailable('The executor must run inside its configured resource slice')
        if not (self.directory / 'cgroup.controllers').is_file():
            raise PoolUnavailable('The resource pool must use cgroup v2')
        return limits(self.directory)


def check_service(unit, *, native_socket=None):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]{0,239}\.service', unit):
        raise PoolUnavailable('Invalid executor service name')
    service = properties(unit)
    # A native system service may rely on systemd's default root user.
    users = ('root', '') if native_socket is not None else ('root',)
    if service.get('ActiveState') != 'active' or service.get('User') not in users:
        raise PoolUnavailable('The installed root executor service must be active')
    pid = service.get('MainPID', '')
    if not pid.isdigit() or int(pid) <= 0 or (Path('/proc') / pid).stat().st_uid != 0:
        raise PoolUnavailable('The executor main process must belong to root')
    current = process_group(pid)
    group = group_path(service.get('ControlGroup'))
    if native_socket is not None and service.get('Delegate') != 'yes':
        raise PoolUnavailable('The native repository service requires cgroup delegation')
    if current != group and not (native_socket is not None and current.startswith(group + '/')):
        raise PoolUnavailable('The executor process does not match its service cgroup')
    ResourcePool(service.get('Slice')).verify(current)
    if native_socket is not None:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
            peer.settimeout(5)
            try:
                peer.connect(str(native_socket))
            except PermissionError as error:
                raise PoolUnavailable(
                    'Deployment account cannot connect to the native socket. Check application-group access.') from error
            server_pid, uid, _ = struct.unpack('3i', peer.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        if (server_pid, uid) != (int(pid), 0):
            raise PoolUnavailable('The native socket peer differs from the selected root service')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--service', required=True)
    parser.add_argument('--native-socket', type=Path, help='verify the delegated service owns this Unix socket')
    args = parser.parse_args()
    try:
        check_service(args.service, native_socket=args.native_socket)
    except PoolUnavailable as error:
        parser.exit(1, str(error) + '\n')
    except (OSError, ValueError, subprocess.SubprocessError):
        parser.exit(1, 'Executor resource pool is unavailable or lacks finite limits\n')
    print('Executor resource pool verified')
