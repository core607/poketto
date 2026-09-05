#!/usr/bin/env python3
"""Local signed execution service. User payloads run only in low-privilege SRT units."""

import argparse
import base64
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import selectors
import socket
import socketserver
import stat
import struct
import subprocess
import threading
import time
import uuid
from dataclasses import dataclass, field

from cryptography.hazmat.primitives.serialization import load_pem_public_key
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey

MAX_FRAME = 1048576
MAX_COMMAND = 65536
MAX_OUTPUT = 65536
IDENTITY = ('principalId', 'accountId', 'workspaceId', 'serverSessionHash')


class Rejected(Exception):
    def __init__(self, code):
        self.code = code


def identifier(value):
    try:
        if str(uuid.UUID(value)) != value:
            raise ValueError()
    except (ValueError, AttributeError, TypeError):
        raise Rejected('INVALID_REQUEST') from None
    return value


def integer(value, minimum, maximum):
    if type(value) is not int or not minimum <= value <= maximum:
        raise Rejected('INVALID_REQUEST')
    return value


def hex_value(value, size):
    if not isinstance(value, str) or re.fullmatch('[0-9a-f]{' + str(size) + '}', value) is None:
        raise Rejected('INVALID_REQUEST')
    return value


def decode(value):
    if not isinstance(value, str) or not re.fullmatch('[A-Za-z0-9_-]+', value):
        raise Rejected('INVALID_REQUEST')
    return base64.urlsafe_b64decode(value + '=' * (-len(value) % 4))


def no_duplicates(items):
    result = {}
    for key, value in items:
        if key in result:
            raise Rejected('INVALID_REQUEST')
        result[key] = value
    return result


def json_read(value):
    return json.loads(value, object_pairs_hook=no_duplicates,
                      parse_constant=lambda _: (_ for _ in ()).throw(Rejected('INVALID_REQUEST')))


@dataclass
class Session:
    id: str
    identity: tuple
    commit: str
    deadline: float
    state: str = 'INITIALIZING'
    reason: str = 'session_closed'
    cancelled: threading.Event = field(default_factory=threading.Event)
    operation: threading.Lock = field(default_factory=threading.Lock)
    unit: str = ''


class Service:
    def __init__(self, public_key, backend, config, clock=time.time):
        self.public_key = public_key
        self.backend = backend
        self.config = config
        self.clock = clock
        self.boot = str(uuid.uuid4())
        self.sessions = {}
        self.requests = {}
        self.executions = set()
        self.revoked_keys = {}
        self.revoked_accounts = {}
        self.closed_leases = {}
        self.lock = threading.RLock()

    def hello(self):
        return {'ok': True, 'version': 1, 'workerBootId': self.boot,
                'maxFrameBytes': MAX_FRAME, 'leaseSeconds': self.config['leaseSeconds'],
                'renewAfterSeconds': self.config['renewAfterSeconds']}

    def validate(self, envelope):
        if set(envelope) != {'payload', 'signature'}:
            raise Rejected('INVALID_REQUEST')
        raw, signature = decode(envelope['payload']), decode(envelope['signature'])
        try:
            self.public_key.verify(signature, raw)
        except Exception:
            raise Rejected('INVALID_SIGNATURE') from None
        p = json_read(raw)
        required = {'version', 'workerBootId', 'appBootId', 'operation', 'requestId',
                    'issuedAt', 'expiresAt', 'principalId', 'accountId', 'workspaceId',
                    'serverSessionHash', 'leaseId', 'data'}
        if not isinstance(p, dict) or set(p) != required or p['version'] != 1:
            raise Rejected('INVALID_REQUEST')
        if p['workerBootId'] != self.boot:
            raise Rejected('WORKER_RESTARTED')
        for key in ('appBootId', 'requestId', 'principalId', 'accountId', 'workspaceId', 'leaseId'):
            identifier(p[key])
        hex_value(p['serverSessionHash'], 64)
        now = self.clock()
        issued = integer(p['issuedAt'], 0, 2**53)
        expires = integer(p['expiresAt'], 0, 2**53)
        if issued > now + 2 or expires <= now or expires <= issued or expires - issued > self.config['leaseSeconds']:
            raise Rejected('LEASE_EXPIRED')
        if p['operation'] not in ('OPEN', 'EXEC', 'RENEW', 'CLOSE', 'REVOKE') or not isinstance(p['data'], dict):
            raise Rejected('INVALID_REQUEST')
        return p, hashlib.sha256(raw).hexdigest()

    def handle(self, envelope):
        request_id = None
        try:
            if envelope == {'version': 1, 'operation': 'HELLO'}:
                return self.hello()
            p, digest = self.validate(envelope)
            request_id = p['requestId']
            with self.lock:
                previous = self.requests.get(request_id)
                if previous:
                    if previous[0] != digest:
                        raise Rejected('REPLAY_CONFLICT')
                    return previous[1] or {'ok': False, 'code': 'REQUEST_IN_PROGRESS', 'requestId': request_id}
                if len(self.requests) >= self.config['maxRequests']:
                    raise Rejected('REQUEST_CAPACITY')
                self.requests[request_id] = (digest, None, p['expiresAt'])
            try:
                answer = self.dispatch(p)
            except Rejected as e:
                answer = {'ok': False, 'code': e.code}
            except Exception:
                # Never expose commands, repository paths, signatures, or host exceptions.
                answer = {'ok': False, 'code': 'EXECUTOR_FAILED'}
            answer['requestId'] = request_id
            with self.lock:
                self.requests[request_id] = (digest, answer, p['expiresAt'])
            return answer
        except (Rejected, ValueError, TypeError, KeyError, UnicodeError) as e:
            answer = {'ok': False, 'code': e.code if isinstance(e, Rejected) else 'INVALID_REQUEST'}
            if request_id:
                answer['requestId'] = request_id
            return answer

    def identity(self, p):
        return tuple(p[k] for k in IDENTITY) + (p['appBootId'],)

    def authorized(self, p):
        if (p['workspaceId'], p['principalId']) in self.revoked_keys or (p['workspaceId'], p['accountId']) in self.revoked_accounts:
            raise Rejected('AUTH_REVOKED')

    def response(self, s):
        return {'ok': True, 'leaseId': s.id, 'state': s.state, 'commit': s.commit}

    def dispatch(self, p):
        op, d = p['operation'], p['data']
        with self.lock:
            if op == 'REVOKE':
                if set(d) != {'keyIds', 'accountIds'} or any(not isinstance(d[k], list) or len(d[k]) > 1000 for k in d):
                    raise Rejected('INVALID_REQUEST')
                keys = {identifier(k) for k in d['keyIds']}
                accounts = {identifier(k) for k in d['accountIds']}
                until = self.clock() + self.config['leaseSeconds'] + 2
                self.revoked_keys.update({(p['workspaceId'], k): until for k in keys})
                self.revoked_accounts.update({(p['workspaceId'], k): until for k in accounts})
                closing = [s for s in self.sessions.values() if s.identity[2] == p['workspaceId'] and
                           (s.identity[0] in keys or s.identity[1] in accounts)]
                for s in closing:
                    self.cancel(s, 'revoked')
                return {'ok': True, 'state': 'CLOSING' if any(s.state != 'CLOSED' for s in closing) else 'CLOSED', 'closedCount': len(closing)}
            self.authorized(p)
            s = self.sessions.get(p['leaseId'])
            if op == 'OPEN':
                if p['leaseId'] in self.closed_leases:
                    raise Rejected('SESSION_CLOSED')
                if set(d) != {'exportId', 'bundleSha256', 'bundleBytes', 'commit'}:
                    raise Rejected('INVALID_REQUEST')
                identifier(d['exportId'])
                hex_value(d['bundleSha256'], 64)
                hex_value(d['commit'], 40)
                integer(d['bundleBytes'], 1, self.config['maxBundleBytes'])
                if s or any(x.identity == self.identity(p) and x.state != 'CLOSED' for x in self.sessions.values()):
                    raise Rejected('SESSION_EXISTS')
                if sum(x.state != 'CLOSED' for x in self.sessions.values()) >= self.config['maxSessions']:
                    raise Rejected('SESSION_CAPACITY')
                s = Session(p['leaseId'], self.identity(p), d['commit'], p['expiresAt'])
                self.sessions[s.id] = s
            else:
                if op == 'CLOSE' and not s:
                    if set(d) - {'reason'} or d.get('reason', 'session_closed') not in ('cancelled', 'session_closed', 'client_shutdown'):
                        raise Rejected('INVALID_REQUEST')
                    self.closed_leases[p['leaseId']] = self.clock() + self.config['leaseSeconds'] + 2
                    return {'ok': True, 'leaseId': p['leaseId'], 'state': 'CLOSED', 'commit': None}
                if not s or s.identity != self.identity(p):
                    raise Rejected('SESSION_NOT_FOUND')
                if op == 'CLOSE':
                    if set(d) - {'reason'} or d.get('reason', 'session_closed') not in ('cancelled', 'session_closed', 'client_shutdown'):
                        raise Rejected('INVALID_REQUEST')
                    self.cancel(s, d.get('reason', 'session_closed'))
                    self.closed_leases[s.id] = self.clock() + self.config['leaseSeconds'] + 2
                    return self.response(s)
                if s.cancelled.is_set() or s.deadline <= self.clock():
                    self.cancel(s, 'lease_expired')
                    raise Rejected('LEASE_EXPIRED')
                if op == 'RENEW':
                    if d:
                        raise Rejected('INVALID_REQUEST')
                    s.deadline = max(s.deadline, p['expiresAt'])
                    return self.response(s)
                if set(d) != {'executionId', 'commit', 'command', 'timeoutMillis'}:
                    raise Rejected('INVALID_REQUEST')
                identifier(d['executionId'])
                execution_key = (s.id, d['executionId'])
                if execution_key in self.executions:
                    raise Rejected('EXECUTION_ALREADY_STARTED')
                if d['commit'] != s.commit:
                    raise Rejected('SESSION_COMMIT_MISMATCH')
                if not isinstance(d['command'], str) or '\0' in d['command'] or not 0 < len(d['command'].encode('utf-8')) <= MAX_COMMAND:
                    raise Rejected('INVALID_REQUEST')
                integer(d['timeoutMillis'], 1, self.config['maxTimeoutMillis'])
                if s.state != 'READY':
                    raise Rejected('SESSION_BUSY')
                if sum(key[0] == s.id for key in self.executions) >= self.config['maxExecutionsPerSession']:
                    raise Rejected('EXECUTION_CAPACITY')
                self.executions.add(execution_key)
                s.state = 'RUNNING'
        try:
            with s.operation:
                if s.cancelled.is_set():
                    raise Rejected('SESSION_CLOSED')
                if op == 'OPEN':
                    self.backend.open(s, d)
                    result = None
                else:
                    result = self.backend.execute(s, d)
                with self.lock:
                    if s.cancelled.is_set():
                        self.backend.close(s)
                        s.state = 'CLOSED'
                    else:
                        s.state = 'READY'
                    answer = self.response(s)
                    if result is not None:
                        answer['result'] = result
                    elif s.state == 'CLOSED':
                        raise Rejected('SESSION_CLOSED')
                    return answer
        except Exception:
            with self.lock:
                self.cancel(s, 'sandbox_failed')
            raise

    def cancel(self, s, reason):
        if s.state == 'CLOSED':
            return
        s.reason = reason
        s.state = 'CLOSING'
        s.cancelled.set()
        # The executor loop observes cancellation. No unmount can race initialization.
        if s.operation.acquire(blocking=False):
            try:
                self.backend.close(s)
                s.state = 'CLOSED'
            finally:
                s.operation.release()

    def sweep(self):
        with self.lock:
            for s in self.sessions.values():
                if s.deadline <= self.clock():
                    self.cancel(s, 'lease_expired')
            # Expired signatures cannot execute again, so their replay entries can leave memory.
            self.requests = {k: v for k, v in self.requests.items() if v[1] is None or v[2] + 2 > self.clock()}
            self.sessions = {k: s for k, s in self.sessions.items() if s.state != 'CLOSED' or s.deadline + 2 > self.clock()}
            self.executions = {key for key in self.executions if key[0] in self.sessions}
            for name in ('revoked_keys', 'revoked_accounts', 'closed_leases'):
                setattr(self, name, {k: deadline for k, deadline in getattr(self, name).items() if deadline > self.clock()})

    def shutdown(self):
        with self.lock:
            for s in self.sessions.values():
                self.cancel(s, 'client_shutdown')


def checked(args, **kwargs):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=15, **kwargs)


class SystemdBackend:
    def __init__(self, config):
        self.c = config
        self.root = Path(config['runtimeRoot'])
        self.sessions = self.root / 'sessions'
        self.records = self.root / 'records'
        self.user = pwd.getpwnam(config['execUser'])
        if self.user.pw_uid == 0:
            raise RuntimeError('Execution account must be unprivileged')
        if self.user.pw_uid == config['appUid'] or config['appGid'] in os.getgrouplist(config['execUser'], self.user.pw_gid):
            raise RuntimeError('Execution account must not share application identity or socket permissions')
        self.root.mkdir(mode=0o750, parents=True, exist_ok=True)
        for path in (self.root, self.sessions, self.records):
            path.mkdir(mode=0o750, exist_ok=True)
            if path.is_symlink() or path.stat().st_uid != 0 or path.stat().st_mode & 0o022:
                raise RuntimeError('Executor root must be root-owned and not writable by other accounts')
        os.chown(self.root, 0, self.user.pw_gid)
        os.chmod(self.root, 0o751)
        os.chown(self.sessions, 0, self.user.pw_gid)
        os.chown(self.records, 0, self.user.pw_gid)
        os.chmod(self.sessions, 0o750)
        os.chmod(self.records, 0o750)
        self.lock_fd = os.open(self.root / '.supervisor.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        fcntl.flock(self.lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        self.cleanup()

    def mount_path(self, s):
        return self.sessions / identifier(s.id)

    def open(self, s, data):
        target = self.mount_path(s)
        target.mkdir(mode=0o750)
        checked(['mount', '-t', 'tmpfs', '-o',
                 f"size={self.c['diskBytes']},nr_inodes={self.c['diskInodes']},mode=0750,nosuid,nodev",
                 'tmpfs', str(target)])
        os.chown(target, 0, self.user.pw_gid)
        bootstrap = target / 'bootstrap'
        bootstrap.mkdir(mode=0o555)
        bootstrap.chmod(0o555)
        # SRT 0.0.75 binds deny markers at these paths. Supply inert root-owned targets
        # so bwrap never needs to write its own startup directory.
        for name in ('.gitconfig', '.gitmodules', '.bashrc', '.bash_profile', '.zshrc',
                     '.zprofile', '.profile', '.ripgreprc', '.mcp.json'):
            marker = bootstrap / name
            marker.touch(mode=0o444)
            marker.chmod(0o444)
        for name in ('.vscode', '.idea', '.claude', '.claude/commands', '.claude/agents'):
            directory = bootstrap / name
            directory.mkdir(mode=0o555)
            directory.chmod(0o555)
        # The untrusted account can replace only children, never the root mountpoint or records.
        for name in ('work', 'home', 'tmp'):
            path = target / name
            path.mkdir(mode=0o700)
            os.chown(path, self.user.pw_uid, self.user.pw_gid)
        exports = os.open(self.c['exportRoot'], os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            fd = os.open(data['exportId'] + '.bundle', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=exports)
        finally:
            os.close(exports)
        digest = hashlib.sha256()
        total = 0
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != self.c['appUid'] or info.st_size != data['bundleBytes']:
                raise Rejected('INVALID_EXPORT')
            with os.fdopen(fd, 'rb', closefd=False) as stream, (target / 'snapshot.bundle').open('xb') as out:
                while True:
                    if s.cancelled.is_set():
                        raise Rejected('SESSION_CLOSED')
                    part = stream.read(65536)
                    if not part:
                        break
                    total += len(part)
                    if total > data['bundleBytes']:
                        raise Rejected('INVALID_EXPORT')
                    digest.update(part)
                    out.write(part)
        finally:
            os.close(fd)
        if total != data['bundleBytes'] or digest.hexdigest() != data['bundleSha256']:
            raise Rejected('INVALID_EXPORT')
        os.chmod(target / 'snapshot.bundle', 0o440)
        os.chown(target / 'snapshot.bundle', 0, self.user.pw_gid)
        result = self.run(s, {'mode': 'initialize', 'commit': s.commit}, self.c['initTimeoutMillis'])
        if result['exitCode'] != 0 or result['terminationReason'] != 'normal':
            raise Rejected('INITIALIZATION_FAILED')
        (target / 'snapshot.bundle').unlink()

    def execute(self, s, data):
        return self.run(s, {'mode': 'execute', 'command': data['command']}, data['timeoutMillis'])

    def run(self, s, payload, timeout_ms):
        target = self.mount_path(s)
        operation = str(uuid.uuid4())
        record = self.records / (operation + '.json')
        settings = self.records / (operation + '.srt.json')
        settings.write_text(json.dumps({'network': {'allowedDomains': [], 'deniedDomains': [], 'allowAllUnixSockets': False},
            'filesystem': {'denyRead': ['/'], 'allowRead': ['/usr', '/bin', '/lib', '/lib64', '/dev', '/proc',
            '/etc/ld.so.cache', str(Path(self.c['toolsRoot'])), str(target)],
            'allowWrite': [str(target / 'work'), str(target / 'home'), '/tmp'], 'denyWrite': []},
            'enableWeakerNestedSandbox': False}))
        record.write_text(json.dumps({**payload, 'root': str(target), 'tools': self.c['toolsRoot'], 'settings': str(settings)}))
        for file in (record, settings):
            os.chmod(file, 0o440)
            os.chown(file, 0, self.user.pw_gid)
        unit = self.c['unitPrefix'] + operation
        s.unit = unit
        args = ['systemd-run', '--quiet', '--wait', '--pipe', '--unit', unit,
                '-p', 'User=' + self.c['execUser'], '-p', 'CPUQuota=' + str(self.c['cpuQuotaPercent']) + '%',
                '-p', 'MemoryMax=' + str(self.c['memoryBytes']), '-p', 'MemorySwapMax=0',
                '-p', 'TasksMax=' + str(self.c['tasksMax']), '-p', f'RuntimeMaxSec={timeout_ms / 1000}',
                '-p', 'KillMode=control-group', '-p', 'TimeoutStopSec=1', '-p', 'SendSIGKILL=yes',
                '-p', 'NoNewPrivileges=yes', '-p', 'UMask=0077',
                '-p', f"TemporaryFileSystem=/tmp:rw,size={self.c['temporaryBytes']},nr_inodes={self.c['temporaryInodes']},mode=1777,nosuid,nodev",
                '-p', 'BindReadOnlyPaths=' + self.c['toolsRoot'] + ' ' + self.c['launcher'] + ' ' + str(record) + ' ' + str(settings),
                '-p', 'BindPaths=' + str(target)]
        if self.c.get('supervisorUnit'):
            args += ['-p', 'BindsTo=' + self.c['supervisorUnit'], '-p', 'After=' + self.c['supervisorUnit']]
        args += ['/usr/bin/env', '-i', 'PATH=/usr/bin:/bin', '/usr/bin/python3', self.c['launcher'], str(record)]
        output = [bytearray(), bytearray()]
        truncated = [False, False]
        reason = 'normal'
        process = None
        try:
            if s.cancelled.is_set():
                raise Rejected('SESSION_CLOSED')
            process = subprocess.Popen(args, stdout=subprocess.PIPE, stderr=subprocess.PIPE, stdin=subprocess.DEVNULL)
            selector = selectors.DefaultSelector()
            selector.register(process.stdout, selectors.EVENT_READ, 0)
            selector.register(process.stderr, selectors.EVENT_READ, 1)
            started = time.monotonic()
            killed = False
            while process.poll() is None or selector.get_map():
                if not killed and (s.cancelled.is_set() or time.monotonic() - started > timeout_ms / 1000):
                    reason = s.reason if s.cancelled.is_set() else 'timeout'
                    subprocess.run(['systemctl', 'kill', '--kill-who=all', '--signal=KILL', unit], capture_output=True, timeout=5)
                    killed = True
                for key, _ in selector.select(0.1):
                    part = os.read(key.fileobj.fileno(), 8192)
                    if not part:
                        selector.unregister(key.fileobj)
                        continue
                    available = MAX_OUTPUT - sum(len(x) for x in output)
                    output[key.data].extend(part[:available])
                    if len(part) > available:
                        truncated[key.data] = True
                        if not killed:
                            reason = 'output_limit'
                            subprocess.run(['systemctl', 'kill', '--kill-who=all', '--signal=KILL', unit], capture_output=True, timeout=5)
                            killed = True
            selector.close()
            process.wait(timeout=5)
            status = checked(['systemctl', 'show', unit, '-p', 'Result', '-p', 'ExecMainStatus']).stdout.decode()
            if reason == 'normal' and 'Result=timeout' in status:
                reason = 'timeout'
            if reason == 'normal' and 'Result=oom-kill' in status:
                reason = 'resource_limit'
            match = re.search(r'ExecMainStatus=(\d+)', status)
            exit_code = int(match.group(1)) if match else process.returncode
            if reason != 'normal':
                if exit_code == 0:
                    exit_code = 124 if reason == 'timeout' else 137
                s.reason = reason
                s.cancelled.set()
            return {'commit': s.commit, 'exitCode': exit_code,
                    'stdout': output[0].decode(errors='replace'), 'stderr': output[1].decode(errors='replace'),
                    'stdoutTruncated': truncated[0], 'stderrTruncated': truncated[1],
                    'timedOut': reason == 'timeout', 'terminationReason': reason}
        finally:
            subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=10)
            self.assert_empty(unit)
            subprocess.run(['systemctl', 'reset-failed', unit], capture_output=True, timeout=5)
            if process:
                process.wait(timeout=5)
            record.unlink(missing_ok=True)
            settings.unlink(missing_ok=True)
            s.unit = ''

    def assert_empty(self, unit):
        result = checked(['systemctl', 'show', unit, '-p', 'ControlGroup', '--value']).stdout.decode().strip()
        if result:
            if not result.startswith('/') or '..' in Path(result).parts:
                raise RuntimeError('Invalid cgroup path')
            root = Path('/sys/fs/cgroup') / result.lstrip('/')
            if root.exists() and any(p.read_text().strip() for p in root.rglob('cgroup.procs')):
                raise RuntimeError('Execution descendants remain')

    def close(self, s):
        if s.unit:
            checked(['systemctl', 'stop', s.unit])
            self.assert_empty(s.unit)
        target = self.mount_path(s)
        if target.is_symlink():
            raise RuntimeError('Unsafe mountpoint')
        if target.is_mount():
            checked(['umount', str(target)])
        if target.exists():
            # Never recurse into a command-controlled directory, even after a failed unmount.
            target.rmdir()

    def cleanup(self):
        units = checked(['systemctl', 'list-units', '--all', '--plain', '--no-legend', self.c['unitPrefix'] + '*']).stdout.decode()
        pattern = re.compile(re.escape(self.c['unitPrefix']) + r'[0-9a-f-]{36}\.service')
        for line in units.splitlines():
            name = line.split()[0]
            if not pattern.fullmatch(name):
                raise RuntimeError('Unexpected executor unit')
            checked(['systemctl', 'stop', name])
            self.assert_empty(name)
            subprocess.run(['systemctl', 'reset-failed', name], capture_output=True, timeout=5)
        for target in self.sessions.iterdir():
            identifier(target.name)
            self.close(Session(target.name, (), '', 0))
        for record in self.records.iterdir():
            if not re.fullmatch(r'[0-9a-f-]{36}(\.srt)?\.json', record.name) or record.is_symlink() or not record.is_file():
                raise RuntimeError('Unexpected executor record')
            record.unlink()


def recv_exact(sock, count):
    result = bytearray()
    while len(result) < count:
        part = sock.recv(count - len(result))
        if not part:
            raise Rejected('INVALID_FRAME')
        result.extend(part)
    return bytes(result)


class Handler(socketserver.BaseRequestHandler):
    def handle(self):
        self.request.settimeout(5)
        try:
            _, uid, _ = struct.unpack('3i', self.request.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
            if uid != self.server.app_uid:
                raise Rejected('PEER_DENIED')
            size, = struct.unpack('!I', recv_exact(self.request, 4))
            if not 0 < size <= MAX_FRAME:
                raise Rejected('INVALID_FRAME')
            response = self.server.service.handle(json_read(recv_exact(self.request, size)))
        except Exception:
            response = {'ok': False, 'code': 'INVALID_FRAME'}
        raw = json.dumps(response, ensure_ascii=True, separators=(',', ':')).encode()
        if len(raw) > MAX_FRAME:
            raw = b'{"ok":false,"code":"RESPONSE_LIMIT"}'
        try:
            self.request.sendall(struct.pack('!I', len(raw)) + raw)
        except OSError:
            pass


class Server(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True
    request_queue_size = 16

    def process_request(self, request, client_address):
        if not self.capacity.acquire(blocking=False):
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except Exception:
            self.capacity.release()
            raise

    def process_request_thread(self, request, client_address):
        try:
            super().process_request_thread(request, client_address)
        finally:
            self.capacity.release()


def load_config(path):
    c = json_read(Path(path).read_bytes())
    for name in ('runtimeRoot', 'exportRoot', 'socketPath', 'publicKey', 'toolsRoot', 'launcher'):
        p = Path(c[name])
        if not p.is_absolute() or '..' in p.parts or not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(p)):
            raise ValueError('Configuration paths must be absolute')
    if Path(c['socketPath']).parent != Path(c['runtimeRoot']):
        raise ValueError('Socket must be directly under the dedicated runtime root')
    for name in ('leaseSeconds', 'renewAfterSeconds', 'maxRequests', 'maxSessions', 'maxBundleBytes',
                 'diskBytes', 'diskInodes', 'memoryBytes', 'tasksMax', 'cpuQuotaPercent', 'maxTimeoutMillis', 'initTimeoutMillis',
                 'maxConnections', 'maxExecutionsPerSession', 'temporaryBytes', 'temporaryInodes'):
        integer(c[name], 1, 2**40)
    if c['renewAfterSeconds'] >= c['leaseSeconds'] or not re.fullmatch(r'poketto-exec-[a-z0-9]+-', c['unitPrefix']):
        raise ValueError('Invalid executor configuration')
    return c


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', required=True)
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('The resource supervisor requires root; execution uses a separate account')
    config = load_config(args.config)
    backend = SystemdBackend(config)
    if args.cleanup:
        return
    key = load_pem_public_key(Path(config['publicKey']).read_bytes())
    if not isinstance(key, Ed25519PublicKey):
        raise SystemExit('Ed25519 public key required')
    service = Service(key, backend, config)
    sock = Path(config['socketPath'])
    if sock.exists():
        if not stat.S_ISSOCK(sock.lstat().st_mode):
            raise SystemExit('Socket path is occupied')
        sock.unlink()
    with Server(str(sock), Handler) as server:
        os.chmod(sock, 0o660)
        os.chown(sock, 0, config['appGid'])
        server.app_uid = config['appUid']
        server.service = service
        server.capacity = threading.BoundedSemaphore(config['maxConnections'])
        threading.Thread(target=server.serve_forever, daemon=True).start()
        try:
            while True:
                service.sweep()
                time.sleep(0.2)
        finally:
            service.shutdown()
            server.shutdown()
            backend.cleanup()
            sock.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
