#!/usr/bin/env python3
"""Separate signed game service: finite jobs, no repository copies or command bridge."""
import argparse
import base64
import json
import os
from pathlib import Path
import pwd
import re
import stat
import threading
import time
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
from resource_pool import ResourcePool
from worker import (Service, Session, SystemdBackend, Handler, Server, Rejected,
                    checked, integer, json_read)

MAX_INPUT = 512 * 1024
MAX_BUNDLE = 384 * 1024


def encoded(value, maximum, depth=0):
    if depth > 16:
        raise Rejected('INVALID_GAME')
    if isinstance(value, dict):
        if len(value) > 2048 or any(not isinstance(k, str) or len(k.encode()) > 256 for k in value):
            raise Rejected('INVALID_GAME')
        for item in value.values():
            encoded(item, maximum, depth + 1)
    elif isinstance(value, list):
        if len(value) > 2048:
            raise Rejected('INVALID_GAME')
        for item in value:
            encoded(item, maximum, depth + 1)
    result = json.dumps(value, ensure_ascii=False, allow_nan=False, separators=(',', ':')).encode()
    if len(result) > maximum:
        raise Rejected('GAME_LIMIT')
    return result


def game_input(data):
    if set(data) != {'bundle', 'request'}:
        raise Rejected('INVALID_GAME')
    bundle, request = data['bundle'], data['request']
    if not isinstance(bundle, dict) or set(bundle) != {'protocol', 'source', 'presentation', 'resources'}:
        raise Rejected('INVALID_GAME')
    if type(bundle['protocol']) is not int or bundle['protocol'] != 1:
        raise Rejected('INVALID_GAME')
    for name, bound in (('source', 256 * 1024), ('presentation', 64 * 1024)):
        value = bundle[name]
        if name == 'presentation' and value is None:
            continue
        if not isinstance(value, str) or not value or len(value.encode()) > bound:
            raise Rejected('INVALID_GAME')
    if not isinstance(bundle['resources'], dict) or len(bundle['resources']) > 32:
        raise Rejected('INVALID_GAME')
    for resource in bundle['resources'].values():
        if not isinstance(resource, dict) or set(resource) != {'mediaType', 'data'}:
            raise Rejected('INVALID_GAME')
        if resource['mediaType'] not in ('image/png', 'image/jpeg', 'image/webp', 'text/plain',
                                         'application/json', 'application/octet-stream'):
            raise Rejected('INVALID_GAME')
        base64.b64decode(resource['data'], validate=True)
    encoded(bundle, MAX_BUNDLE)
    if not isinstance(request, dict) or set(request) != {'mode', 'state', 'action', 'seed'}:
        raise Rejected('INVALID_GAME')
    mode = request['mode']
    if mode not in ('init', 'observe', 'act'):
        raise Rejected('INVALID_GAME')
    if mode == 'init':
        integer(request['seed'], 0, 0xffffffff)
    encoded(request['state'], 32 * 1024)
    if mode == 'act' and (not isinstance(request['action'], str) or len(request['action'].encode()) > 128):
        raise Rejected('INVALID_GAME')
    return encoded(data, MAX_INPUT)


class GameService(Service):
    OPERATIONS = ('GAME', 'REVOKE')

    def __init__(self, public_key, backend, config, clock=time.time):
        super().__init__(public_key, backend, config, clock)
        self.stopping = False

    def hello(self):
        return {'ok': True, 'version': 1, 'gameProtocol': 1, 'codeActProtocol': 0,
                'workerBootId': self.boot, 'maxFrameBytes': 1048576,
                'leaseSeconds': self.config['leaseSeconds'], 'renewAfterSeconds': self.config['renewAfterSeconds']}

    def dispatch(self, request):
        if request['operation'] == 'REVOKE':
            return super().dispatch(request)
        data = game_input(request['data'])
        session = Session(request['leaseId'], self.identity(request), '', request['expiresAt'])
        with self.lock:
            if self.stopping:
                raise Rejected('WORKER_STOPPING')
            self.authorized(request)
            if session.id in self.sessions:
                raise Rejected('GAME_BUSY')
            if len(self.sessions) >= self.config['maxSessions']:
                raise Rejected('GAME_CAPACITY')
            self.sessions[session.id] = session
            session.operation.acquire()
        try:
            try:
                result = self.backend.game(session, data)
                if self.clock() >= session.deadline or session.cancelled.is_set():
                    raise Rejected('GAME_EXPIRED')
                return {'ok': True, 'result': result}
            finally:
                # Admission is released only after the inherited cgroup-empty check.
                # A containment failure leaves the slot occupied until supervisor recovery.
                self.backend.close(session)
                with self.lock:
                    self.sessions.pop(session.id, None)
        finally:
            session.operation.release()

    def sweep(self):
        with self.lock:
            now = self.clock()
            for key, (_, answer, expires) in list(self.requests.items()):
                if expires <= now and answer is not None:
                    self.requests.pop(key)
            for session in self.sessions.values():
                if session.deadline <= now:
                    session.reason = 'lease_expired'
                    session.cancelled.set()
            for revoked in (self.revoked_keys, self.revoked_accounts):
                for key, expires in list(revoked.items()):
                    if expires <= now:
                        revoked.pop(key)

    def shutdown(self):
        with self.lock:
            self.stopping = True
            active = list(self.sessions.values())
            for session in self.sessions.values():
                session.reason = 'client_shutdown'
                session.cancelled.set()
        deadline = time.monotonic() + 15
        for session in active:
            if not session.operation.acquire(timeout=max(0, deadline - time.monotonic())):
                raise RuntimeError('Game handler did not stop; preserve its files for supervisor cleanup')
            session.operation.release()


class GameBackend(SystemdBackend):
    output_limit = 96 * 1024

    def run_output(self, output, truncated):
        if any(truncated):
            raise Rejected('GAME_LIMIT')
        return {'stdout': bytes(output[0]).decode('utf-8'), 'stderr': bytes(output[1]).decode('utf-8', errors='replace')}

    def game(self, session, data):
        self.pool.verify()
        target = self.mount_path(session)
        target.mkdir(mode=0o750)
        checked(['mount', '-t', 'tmpfs', '-o', 'size=2097152,nr_inodes=128,mode=0750,nosuid,nodev,noexec',
                 'tmpfs', str(target)])
        bootstrap = self.prepare_bootstrap(target)
        for name in ('game-entry.mjs', 'game-runtime.mjs'):
            destination = bootstrap / name
            destination.write_bytes(Path(self.c['launcher']).with_name(name).read_bytes())
            destination.chmod(0o444)
        input_path = target / 'input.json'
        input_path.write_bytes(data)
        input_path.chmod(0o440)
        os.chown(input_path, 0, self.user.pw_gid)
        result = self.run(session, {'mode': 'game'}, self.c['maxTimeoutMillis'])
        if result['exitCode'] != 0 or result['terminationReason'] != 'normal':
            raise Rejected('GAME_FAILED')
        output = result['stdout']
        if len(output.encode()) > 96 * 1024:
            raise Rejected('GAME_LIMIT')
        value = json_read(output)
        if not isinstance(value, dict) or set(value) != {'state', 'observation', 'presentation'}:
            raise Rejected('INVALID_GAME_OUTPUT')
        encoded(value['state'], 32 * 1024)
        encoded(value['observation'], 32 * 1024)
        encoded(value['presentation'], 32 * 1024)
        return value

    def sandbox_policy(self, session, payload):
        target = self.mount_path(session)
        return {'network': {'allowedDomains': [], 'deniedDomains': [], 'allowAllUnixSockets': False},
                'filesystem': {'denyRead': ['/'], 'allowRead': [
                    '/usr', '/bin', '/lib', '/lib64', '/dev', '/proc', '/etc/ld.so.cache',
                    self.c['toolsRoot'], str(target / 'bootstrap'), str(target / 'input.json')],
                    'allowWrite': ['/tmp'], 'denyWrite': []}, 'enableWeakerNestedSandbox': False}


def load_game_config(path):
    config = json_read(Path(path).read_bytes())
    for name in ('runtimeRoot', 'socketPath', 'publicKey', 'toolsRoot', 'launcher', 'repositoryRuntimeRoot'):
        value = Path(config[name])
        if not value.is_absolute() or '..' in value.parts or not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(value)):
            raise ValueError('Game configuration requires absolute safe paths')
    root, repository = Path(config['runtimeRoot']), Path(config['repositoryRuntimeRoot'])
    if root.is_relative_to(repository) or repository.is_relative_to(root):
        raise ValueError('Game runtime must be separate from the repository worker')
    if Path(config['socketPath']).parent != root:
        raise ValueError('Game socket must be below its dedicated runtime root')
    if pwd.getpwnam(config['execUser']).pw_uid == pwd.getpwnam(config['repositoryExecUser']).pw_uid:
        raise ValueError('Games need a separate execution account')
    if config['resourceSlice'] == config['repositoryResourceSlice']:
        raise ValueError('Games need a separate resource slice')
    if not re.fullmatch(r'poketto-game-[a-z0-9]+-', config['unitPrefix']):
        raise ValueError('Games need a separate unit prefix')
    for name in ('memoryBytes', 'tasksMax', 'cpuQuotaPercent', 'temporaryBytes', 'temporaryInodes',
                 'maxRequests', 'maxConnections'):
        integer(config[name], 1, 2**40)
    integer(config['maxSessions'], 1, 16)
    # SRT and Node count their own threads against this limit before any rule runs.
    integer(config['tasksMax'], 48, 256)
    integer(config['maxTimeoutMillis'], 100, 5000)
    integer(config['leaseSeconds'], 6, 30)
    integer(config['renewAfterSeconds'], 1, 5)
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--cleanup', action='store_true')
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise SystemExit('The game supervisor requires root; rule execution never uses root')
    config = load_game_config(args.config)
    backend = GameBackend(config)
    if args.cleanup:
        return
    backend.pool = ResourcePool(config['resourceSlice'])
    backend.pool.verify()
    key = load_pem_public_key(Path(config['publicKey']).read_bytes())
    if not isinstance(key, Ed25519PublicKey):
        raise SystemExit('Ed25519 public key required')
    service = GameService(key, backend, config)
    sock = Path(config['socketPath'])
    if sock.exists():
        if not stat.S_ISSOCK(sock.lstat().st_mode):
            raise SystemExit('Game socket path is occupied')
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
                time.sleep(0.1)
        finally:
            service.shutdown()
            server.shutdown()
            backend.cleanup()
            sock.unlink(missing_ok=True)


if __name__ == '__main__':
    main()
