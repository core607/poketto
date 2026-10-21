"""Differential signed frames, identity and admission checks; no sandbox execution."""
import argparse
import base64
import json
from pathlib import Path
import select
import socket
import struct
import subprocess
import sys
import tempfile
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace

parser = argparse.ArgumentParser()
parser.add_argument('--source', required=True)
parser.add_argument('--rust', required=True)
args = parser.parse_args()
sys.path.insert(0, args.source)
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from worker import Service, Session, json_read, recv_exact


def uid():
    return str(uuid.uuid4())


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def fixture(key, count=1, maximum=4096):
    identity = {name: uid() for name in ('appBootId', 'principalId', 'accountId', 'workspaceId')}
    identity['serverSessionHash'] = 'b' * 64
    return {'fixtureOnly': True, 'publicKey': b64(key.public_key().public_bytes_raw()),
            'workerBootId': uid(), 'maxRequests': maximum, 'maxConnections': 256,
            'sessions': [{**identity, 'leaseId': uid(), 'deadline': time.time() + 15} for _ in range(count)]}


def python_service(key, settings):
    backend = SimpleNamespace(close=lambda _: None)
    service = Service(key.public_key(), backend, {'maxRequests': settings['maxRequests'], 'leaseSeconds': 15})
    service.boot = settings['workerBootId']
    for row in settings['sessions']:
        service.sessions[row['leaseId']] = Session(row['leaseId'], service.identity(row), 'a' * 40,
                                                  row['deadline'], state='READY')
    service.revoked_keys = {tuple(v): time.time() + 15 for v in settings.get('revokedKeys', [])}
    service.revoked_accounts = {tuple(v): time.time() + 15 for v in settings.get('revokedAccounts', [])}
    return service


def payload(settings, index=0):
    row = {k: v for k, v in settings['sessions'][index % len(settings['sessions'])].items()
           if k in ('appBootId', 'principalId', 'accountId', 'workspaceId', 'serverSessionHash', 'leaseId')}
    issued = int(time.time())
    return {**row, 'version': 1, 'workerBootId': settings['workerBootId'], 'requestId': uid(),
            'operation': 'RENEW', 'issuedAt': issued, 'expiresAt': issued + 15, 'data': {}}


def envelope(key, value=None, raw=None):
    raw = json.dumps(value, separators=(',', ':')).encode() if raw is None else raw
    return json.dumps({'payload': b64(raw), 'signature': b64(key.sign(raw))}, separators=(',', ':')).encode()


def exchange(path, raw, declared=None):
    with socket.socket(socket.AF_UNIX) as stream:
        stream.settimeout(7)
        stream.connect(path)
        stream.sendall(struct.pack('!I', len(raw) if declared is None else declared) + raw)
        count, = struct.unpack('!I', recv_exact(stream, 4))
        assert count <= 1048576
        return json.loads(recv_exact(stream, count))


class RustServer:
    def __init__(self, mode, path, settings):
        self.process = subprocess.Popen([args.rust, path, mode], stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.send(json.dumps(settings))
        assert self.read()['ready']

    def send(self, line):
        self.process.stdin.write(line + '\n')
        self.process.stdin.flush()

    def read(self):
        assert select.select([self.process.stdout], [], [], 8)[0], 'Rust control response timed out'
        line = self.process.stdout.readline()
        if not line:
            raise RuntimeError(self.process.stderr.read()[-1500:])
        return json.loads(line)

    def command(self, value):
        self.send(value)
        return self.read()

    def close(self):
        if self.process.poll() is None:
            try:
                assert self.command('stop')['stopped']
                self.process.wait(timeout=8)
            finally:
                if self.process.poll() is None:
                    self.process.kill()
                    self.process.wait(timeout=3)
        assert self.process.returncode == 0


def differential():
    key = Ed25519PrivateKey.generate()
    settings = fixture(key)
    service = python_service(key, settings)
    count = 0
    with tempfile.TemporaryDirectory() as temporary:
        path = temporary + '/rust.sock'
        rust = RustServer('threads', path, settings)
        try:
            def check(name, raw):
                nonlocal count
                try:
                    expected = service.handle(json_read(raw))
                except Exception:
                    expected = {'ok': False, 'code': 'INVALID_FRAME'}
                actual = exchange(path, raw)
                assert actual == expected, (name, actual, expected)
                count += 1

            check('valid', envelope(key, payload(settings)))
            original = payload(settings)
            check('replay-first', envelope(key, original))
            check('replay-same', envelope(key, original))
            check('replay-conflict', envelope(key, {**original, 'data': {'changed': 1}}))
            for name in payload(settings):
                p = payload(settings); p.pop(name)
                check('missing-' + name, envelope(key, p))
            for name in ('appBootId', 'principalId', 'accountId', 'workspaceId', 'leaseId', 'serverSessionHash'):
                p = payload(settings); p[name] = 'f' * 64 if name == 'serverSessionHash' else uid()
                check('foreign-' + name, envelope(key, p))
            mutations = [('version', 2), ('version', 1.0), ('version', True), ('version', False),
                         ('requestId', 'BAD'), ('principalId', 'AAAAAAAA-0000-0000-0000-000000000000'),
                         ('serverSessionHash', 'B' * 64), ('workerBootId', uid()), ('operation', 'UNKNOWN'),
                         ('data', None), ('data', []), ('data', {'extra': 1}),
                         ('issuedAt', True), ('issuedAt', 1.0), ('issuedAt', -1), ('issuedAt', 2**53 + 1),
                         ('expiresAt', True), ('expiresAt', 0), ('expiresAt', '1')]
            for name, value in mutations:
                p = payload(settings); p[name] = value
                check('invalid-' + name + '-' + repr(value), envelope(key, p))
            for changes in ({'extra': 1}, {'issuedAt': int(time.time()) + 5},
                            {'expiresAt': int(time.time()) - 1}, {'expiresAt': int(time.time()) + 20}):
                check('bounds', envelope(key, {**payload(settings), **changes}))
            p = payload(settings)
            raw = json.dumps(p).encode().replace(b'"data": {}', b'"data":{"a":1,"a":2}')
            check('nested-duplicate', envelope(key, raw=raw))
            raw = json.dumps(p).encode()[:-1] + b',"version":1}'
            check('payload-duplicate', envelope(key, raw=raw))
            check('outer-duplicate', b'{"payload":"a","payload":"b","signature":"a"}')
            check('bad-outer-json', b'{')
            check('non-object-outer', b'[]')
            check('missing-envelope-field', b'{"payload":"a"}')
            for value in ('', 'a', 'YQ==', '+a', False):
                raw = json.loads(envelope(key, payload(settings)));raw['payload'] = value
                check('invalid-encoding', json.dumps(raw).encode())
            raw = json.loads(envelope(key, payload(settings)));raw['signature'] = b64(b'bad')
            check('invalid-signature-length', json.dumps(raw).encode())
            other = Ed25519PrivateKey.generate()
            check('wrong-key', envelope(other, payload(settings)))
            assert exchange(path, b'', 0) == {'ok': False, 'code': 'INVALID_FRAME'}
            assert exchange(path, b'', 1048577) == {'ok': False, 'code': 'INVALID_FRAME'}
            count += 2
            original = payload(settings)
            raw = envelope(key, original)
            rust.command('start')
            before = rust.command('stats')['requests']
            python_before = len(service.requests)
            with ThreadPoolExecutor(max_workers=16) as clients:
                answers = list(clients.map(lambda _: exchange(path, raw), range(32)))
            with ThreadPoolExecutor(max_workers=16) as clients:
                python_answers = list(clients.map(lambda _: service.handle(json_read(raw)), range(32)))
            for batch in (answers, python_answers):
                assert any(row['ok'] for row in batch)
                assert all(row['ok'] or row.get('code') == 'REQUEST_IN_PROGRESS' for row in batch)
            assert rust.command('stats')['requests'] == before + 1
            assert len(service.requests) == python_before + 1
            count += 1
            expiring = payload(settings)
            expiring['expiresAt'] = int(time.time()) + 1
            check('short-lived-replay-entry', envelope(key, expiring))
            time.sleep(max(0, expiring['expiresAt'] + 2.4 - time.time()))
            service.sweep()
            renewed = payload(settings);renewed['requestId'] = expiring['requestId']
            check('replay-entry-expired', envelope(key, renewed))
        finally:
            rust.close()
    for variant in ('capacity', 'expired', 'revoked-key', 'revoked-account'):
        settings = fixture(key, maximum=1 if variant == 'capacity' else 4096)
        if variant == 'expired':
            settings['sessions'][0]['deadline'] = time.time() - 1
        if variant.startswith('revoked'):
            row = settings['sessions'][0]
            settings['revokedKeys' if variant == 'revoked-key' else 'revokedAccounts'] = [
                [row['workspaceId'], row['principalId' if variant == 'revoked-key' else 'accountId']]]
        service = python_service(key, settings)
        with tempfile.TemporaryDirectory() as temporary:
            path = temporary + '/rust.sock'; rust = RustServer('pool', path, settings)
            try:
                for _ in range(2):
                    raw = envelope(key, payload(settings))
                    actual = exchange(path, raw); expected = service.handle(json_read(raw))
                    assert actual == expected, (variant, actual, expected)
                    count += 1
            finally:
                rust.close()
    print(json.dumps({'differentialCases': count, 'result': 'PASS',
                      'scope': 'RENEW/framing validation, replay, identity, expiry, capacity and seeded revocation; no EXEC/sandbox/durability.'}), flush=True)



if __name__ == '__main__':
    differential()
