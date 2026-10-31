#!/usr/bin/env python3
"""Disposable XFS journal and real operator helper; native HTTP and host-containment proof are fixtures."""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler
import io
import json
import os
from pathlib import Path
import select
import shutil
import socketserver
import subprocess
import sys
import tarfile
import tempfile
import threading
import time
import uuid


class NativeFixture(socketserver.ThreadingMixIn, socketserver.UnixStreamServer):
    daemon_threads = True

    def __init__(self, socket, destination):
        super().__init__(str(socket), Handler)
        self.destination = destination
        self.epoch = str(uuid.uuid4())
        self.volumes, self.sessions, self.uploads = {}, {}, {}
        self.requests, self.puts = [], []
        self.drop_work = True


class Handler(BaseHTTPRequestHandler):
    protocol_version = 'HTTP/1.1'

    def log_message(self, *unused):
        pass

    def answer(self, value, status=200):
        data = json.dumps(value).encode()
        self.send_response(status)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        self.server.requests.append(('GET', self.path))
        if self.path == '/v1/info':
            self.answer({'apiVersion': 1, 'serverId': self.server.epoch,
                         'capabilities': ['runs', 'sessions', 'volumes', 'files', 'calls'],
                         'profiles': {'repository': {'timeoutMillis': 60000, 'outputLimitBytes': 65536,
                                      'network': 'none', 'sessionTtlMillis': 120000, 'transferBytes': 1048576,
                                      'hostCalls': True}}})
            return
        kind, identifier = self.path.split('/')[2:4]
        value = getattr(self.server, kind).get(identifier)
        self.answer(value if value else {'error': {'code': 'UPLOAD_NOT_FOUND'}}, 200 if value else 404)

    def do_POST(self):
        self.server.requests.append(('POST', self.path))
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        if self.path == '/v1/volumes':
            value = self.server.volumes.setdefault(body['id'], {
                **{k: body[k] for k in ('id', 'profile', 'byteLimit', 'inodeLimit', 'cloneFrom')},
                'state': 'ready', 'code': None, 'planDigest': 'b' * 64, 'busy': False, 'attachedTo': None})
        elif self.path == '/v1/sessions':
            value = self.server.sessions.setdefault(body['id'], {
                **{k: body[k] for k in ('id', 'serverId', 'profile', 'volumeId')}, 'state': 'ready',
                'expiresAtMillis': int(time.time() * 1000) + body['ttlMillis'], 'activeRunId': None,
                'parentSessionId': None, 'code': None})
        elif self.path == '/v1/uploads':
            session = self.server.sessions[body['sessionId']]
            assert session['state'] == 'ready'
            value = self.server.uploads.setdefault(body['id'], {
                **{k: body[k] for k in ('id', 'serverId', 'sessionId', 'path', 'format', 'bytes', 'sha256')},
                'profile': session['profile'], 'volumeId': session['volumeId'], 'planDigest': 'c' * 64,
                'state': 'waiting', 'code': None, 'publication': 'none'})
        elif self.path.endswith('/close'):
            value = self.server.sessions[self.path.split('/')[3]]
            value['state'] = 'closed'
        elif self.path.endswith('/forget'):
            value = {'forgotten': True}
        else:
            raise AssertionError('Operator attempted an unsupported native operation')
        self.answer(value)

    def do_PUT(self):
        self.server.requests.append(('PUT', self.path))
        value = self.server.uploads[self.path.split('/')[3]]
        content = self.rfile.read(int(self.headers['Content-Length']))
        assert len(content) == value['bytes'] and hashlib.sha256(content).hexdigest() == value['sha256']
        self.server.puts.append(value['path'])
        target = self.server.destination / value['volumeId'] / value['path']
        target.mkdir(parents=True)
        with tarfile.open(fileobj=io.BytesIO(content)) as archive:
            archive.extractall(target, filter='data')
        value.update(state='succeeded', publication='committed')
        if value['path'] == 'work' and self.server.drop_work:
            self.server.drop_work = False
            self.close_connection = True
            return
        self.answer(value)


def command(*args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30).stdout


def fixture_jvm(runtime, metadata, copy, action, wait=True):
    args = [shutil.which('java'), '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars' / '*'),
            'io.github.core607.poketto.executor.internal.NativeMigrationJournalProbe', str(metadata), copy, action]

    def user():
        os.setgroups([])
        os.setgid(65534)
        os.setuid(65534)

    process = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               preexec_fn=user, env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'})
    if not wait:
        assert select.select([process.stdout], [], [], 15)[0]
        json.loads(process.stdout.readline())
        return process
    output, error = process.communicate(timeout=30)
    assert process.returncode == 0, error.decode()
    return json.loads(output)


def verify(root, runtime, service):
    sys.path.insert(0, str(runtime / 'migration-tests'))
    sys.path.insert(0, str(service))
    import migrate_native_repository as controller
    from native_migration_files import Deferred, HeldLock, identity, inventory

    source = root / 'source'
    (source / 'work' / 'repository').mkdir(parents=True)
    (source / 'home').mkdir()
    (source / 'work' / 'repository' / 'unsaved').write_text('unsaved before import')
    (source / 'work' / 'extra').write_text('outside repository')
    (source / 'home' / '.settings').write_text('retained home')
    metadata = root / 'pool' / 'metadata'
    metadata.mkdir(mode=0o700)
    os.chown(metadata, 65534, 65534)
    socket = root / 'native.sock'
    server = NativeFixture(socket, root / 'volumes')
    os.chown(socket, 0, 65534)
    os.chmod(socket, 0o660)
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        copy = str(uuid.uuid4())
        before = fixture_jvm(runtime, metadata, copy, 'create')
        request = {'operation': str(uuid.uuid4()), 'appUid': 65534, 'appGid': 65534, 'metadata': str(metadata),
                   'limits': {'copies': 128, 'recordBytes': 16777216, 'poolBytes': 536870912, 'idleMillis': 604800000,
                              'originalFiles': {'archiveBytes': 67108864, 'expandedBytes': 268435456, 'entries': 100000}},
                   'owner': before['owner'], 'copyId': copy, 'socket': str(socket), 'profile': 'repository',
                   'volumeBytes': 8388608, 'volumeInodes': 256, 'java': shutil.which('java'), 'runtime': str(runtime)}
        operation = root / 'operation'
        operation.mkdir(mode=0o710)
        os.chown(operation, 0, 65534)
        expected_source = inventory(source)
        with HeldLock(root / 'deploy.lock', create=True) as deployment, \
                HeldLock(source / '.lease.lock', create=True) as source_lock, \
                HeldLock(operation / 'lock', create=True) as own:
            class Guard:
                source_identity = identity(source)
                state_sha = 'a' * 64
                allowed = True

                def bind(self, record):
                    assert record['owner'] == before['owner'] and record['copyId'] == copy

                def require(self):
                    deployment.require_held()
                    source_lock.require_held()
                    if not self.allowed:
                        raise Deferred('FIXTURE_CONTAINMENT_UNPROVEN')

            guard = Guard()
            guard.request, guard.source = request, source
            assert controller.helper(operation, request, guard, source_lock, deployment, own, False)['status'] == 'prepared'
            assert fixture_jvm(runtime, metadata, copy, 'read') == before
            assert not any(method != 'GET' for method, path in server.requests)
            print(json.dumps({'prepareRealJournalNoMutation': 'PASS'}), flush=True)
            blocker = fixture_jvm(runtime, metadata, copy, 'lock', wait=False)
            try:
                try:
                    controller.helper(operation, request, guard, source_lock, deployment, own, True)
                    raise AssertionError('Concurrent account journal was accepted')
                except Deferred:
                    pass
            finally:
                blocker.stdin.close()
                assert blocker.wait(timeout=10) == 0
                blocker.stdout.close()
                blocker.stderr.close()
            print(json.dumps({'realJournalReentryRejected': 'PASS'}), flush=True)
            guard.allowed = False
            try:
                controller.helper(operation, request, guard, source_lock, deployment, own, True)
                raise AssertionError('Missing containment was accepted')
            except Deferred:
                pass
            guard.allowed = True
            assert fixture_jvm(runtime, metadata, copy, 'read') == before
            print(json.dumps({'lostControllerFencePreservesJournal': 'PASS'}), flush=True)
            try:
                controller.helper(operation, request, guard, source_lock, deployment, own, True)
                raise AssertionError('Lost upload response was not surfaced')
            except Deferred:
                pass
            assert fixture_jvm(runtime, metadata, copy, 'read') == before
            assert server.puts == ['work']
            result = controller.helper(operation, request, guard, source_lock, deployment, own, True)
            assert result['status'] == 'published' and server.puts == ['work', 'home']
            after = fixture_jvm(runtime, metadata, copy, 'read')
            for field in before:
                if field not in ('writer', 'revision'):
                    assert after[field] == before[field], field
            assert after['revision'] == before['revision'] + 1
            assert after['writer']['storage']['volumeId'] == copy
            assert inventory(source) == expected_source
            for path in ('work/repository/unsaved', 'work/extra', 'home/.settings'):
                assert (server.destination / copy / path).read_bytes() == (source / path).read_bytes()
            assert all('/commands' not in path and '/calls' not in path for method, path in server.requests)
            print(json.dumps({'partialSucceededReceiptRealJournalRecovery': 'PASS', 'sourcePreserved': True}), flush=True)
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', required=True, type=Path)
    parser.add_argument('--service', required=True, type=Path)
    args = parser.parse_args()
    assert os.geteuid() == 0
    with tempfile.TemporaryDirectory(prefix='poketto-migration-fixture-', dir='/var/lib') as temporary:
        root = Path(temporary)
        root.chmod(0o711)
        pool = root / 'pool'
        pool.mkdir(mode=0o711)
        image = root / 'pool.img'
        command('fallocate', '-l', '512M', str(image))
        command('mkfs.xfs', '-f', str(image))
        command('mount', '-o', 'loop,prjquota,nosuid,nodev', str(image), str(pool))
        try:
            pool.chmod(0o711)
            verify(root, args.runtime.resolve(), args.service.resolve())
        finally:
            command('umount', str(pool))
    print(json.dumps({'cleanup': 'PASS', 'scope': 'real XFS journal and helper; fixture native HTTP and host fence'}))


if __name__ == '__main__':
    main()
