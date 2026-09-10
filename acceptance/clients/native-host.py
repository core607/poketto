#!/usr/bin/env python3
"""Hold disposable Spring/PostgreSQL/SRT services for real MCP clients on a Linux host."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.request
import uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption

POSTGRES = 'postgres:17.11-bookworm@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0'
MODULES = ('worker.py', 'launcher.py', 'resource_pool.py', 'bridge.py', 'cli.py',
           'session_files.py', 'binary_capture.py', 'materialize.py')


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=60, **kwargs).stdout.strip()


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        while block := stream.read(65536):
            digest.update(block)
    return digest.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'worker-source', 'tools', 'java'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, default=38189)
    parser.add_argument('--lifetime-seconds', type=int, default=1800)
    args = parser.parse_args()
    assert os.geteuid() == 0 and 1024 <= args.port <= 65535 and 60 <= args.lifetime_seconds <= 3600
    runtime, source, tools, java = [p.resolve(strict=True) for p in
                                  (args.runtime, args.worker_source, args.tools, args.java)]
    expected = set()
    for line in (runtime / 'manifest.sha256').read_text().split('\n'):
        if not line:
            continue
        digest, relative = line.split('  ', 1)
        target = (runtime / relative).resolve(strict=True)
        assert target.is_relative_to(runtime) and sha(target) == digest
        expected.add(relative)
    assert expected == {str(p.relative_to(runtime)) for p in runtime.rglob('*')
                        if p.is_file() and p.name != 'manifest.sha256'}
    with socket.socket() as port_check:
        port_check.bind(('127.0.0.1', args.port))
    sys.path.insert(0, str(source))
    from native_pool import NativePool
    token = uuid.uuid4().hex[:8]
    pool = NativePool(token)
    root = Path(tempfile.mkdtemp(prefix='poketto-client-', dir='/var/lib'))
    root.chmod(0o751)
    app_user, exec_user = 'pkt-capp-' + token, 'pkt-cexec-' + token
    worker_unit, app_unit, db = ['poketto-client-' + token + '-' + name for name in ('worker', 'app', 'db')]
    users, db_attempted, worker_config = [], False, None
    try:
        pool.start()
        for user in (app_user, exec_user):
            run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
            users.append(user)
        app_account = pwd.getpwnam(app_user)
        for name in ('exports', 'content', 'home'):
            path = root / name
            path.mkdir(mode=0o700)
            os.chown(path, app_account.pw_uid, app_account.pw_gid)
        for name in MODULES:
            shutil.copyfile(source / name, root / name)
            (root / name).chmod(0o644)
        key = Ed25519PrivateKey.generate()
        private = root / 'private.pem'
        private.write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        private.chmod(0o600)
        os.chown(private, app_account.pw_uid, app_account.pw_gid)
        (root / 'public.pem').write_bytes(key.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
        worker_config = root / 'worker.json'
        worker_config.write_text(json.dumps({
            'runtimeRoot': str(root / 'runtime'), 'exportRoot': str(root / 'exports'),
            'socketPath': str(root / 'runtime/control.sock'), 'publicKey': str(root / 'public.pem'),
            'toolsRoot': str(tools), 'launcher': str(root / 'launcher.py'), 'execUser': exec_user,
            'appUid': app_account.pw_uid, 'appGid': app_account.pw_gid,
            'unitPrefix': 'poketto-exec-c' + token + '-', 'supervisorUnit': worker_unit + '.service',
            'resourceSlice': pool.name, 'leaseSeconds': 30, 'renewAfterSeconds': 10,
            'maxRequests': 8192, 'maxConnections': 32, 'maxExecutionsPerSession': 128,
            'maxSessions': 2, 'maxBundleBytes': 16777216, 'diskBytes': 67108864,
            'diskInodes': 8192, 'temporaryBytes': 8388608, 'temporaryInodes': 1024,
            'memoryBytes': 201326592, 'tasksMax': 48, 'cpuQuotaPercent': 50,
            'maxTimeoutMillis': 60000, 'initTimeoutMillis': 15000}))
        run(['systemd-run', '--quiet', '--unit', worker_unit, '--slice', pool.name,
             '-p', 'User=root', '-p', 'UMask=0077',
             '-p', 'Environment=PYTHONPATH=' + str(tools / 'python'),
             '-p', f'ExecStopPost=/usr/bin/python3 {root}/worker.py --config {worker_config} --cleanup',
             '/usr/bin/python3', str(root / 'worker.py'), '--config', str(worker_config)])
        password = secrets.token_urlsafe(32)
        environment = root / 'database.env'
        environment.write_text('POSTGRES_USER=acceptance\nPOSTGRES_DB=acceptance\nPOSTGRES_PASSWORD=' + password + '\n')
        environment.chmod(0o600)
        db_attempted = True
        run(['docker', 'run', '-d', '--name', db, '--label', 'poketto.acceptance=' + token,
             '--memory=256m', '--memory-swap=256m', '--pids-limit=128', '--cpus=1',
             '--tmpfs', '/var/lib/postgresql/data:rw,size=256m', '-p', '127.0.0.1::5432',
             '--env-file', str(environment), POSTGRES])
        binding = run(['docker', 'port', db, '5432/tcp'])
        assert binding.startswith('127.0.0.1:') and '\n' not in binding
        deadline = time.monotonic() + 40
        while subprocess.run(['docker', 'exec', db, 'pg_isready', '-U', 'acceptance', '-d', 'acceptance'],
                             capture_output=True, timeout=5).returncode:
            assert time.monotonic() < deadline, 'disposable PostgreSQL did not become ready'
            time.sleep(.25)
        app_environment = root / 'app.env'
        app_environment.write_text('\n'.join([
            'SPRING_DATASOURCE_URL=jdbc:postgresql://' + binding + '/acceptance',
            'SPRING_DATASOURCE_USERNAME=acceptance', 'SPRING_DATASOURCE_PASSWORD=' + password,
            'POKETTO_ACCEPTANCE_ROOT=' + str(root / 'content'), 'POKETTO_ACCEPTANCE_PASSWORD=' + password,
            'POKETTO_ACCEPTANCE_ORIGIN=http://127.0.0.1:' + str(args.port),
            'POKETTO_SESSION_COOKIE_SECURE=false', 'HOME=' + str(root / 'home')]) + '\n')
        app_environment.chmod(0o600)
        run(['systemd-run', '--quiet', '--unit', app_unit, '-p', 'User=' + app_user,
             '-p', 'EnvironmentFile=' + str(app_environment), '-p', 'UMask=0077',
             '-p', 'MemoryMax=768M', '-p', 'TasksMax=256', '-p', 'CPUQuota=100%',
             '-p', 'RuntimeMaxSec=' + str(args.lifetime_seconds),
             str(java), '-Xmx384m', '-XX:MaxMetaspaceSize=192m', '-Duser.home=' + str(root / 'home'),
             '-Djava.awt.headless=true', '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars/*'),
             'io.github.core607.poketto.acceptance.AcceptanceApplication',
             '--server.address=127.0.0.1', '--server.port=' + str(args.port),
             '--poketto.executor.enabled=true', '--poketto.executor.socket=' + str(root / 'runtime/control.sock'),
             '--poketto.executor.signing-key=' + str(private),
             '--poketto.executor.staging-directory=' + str(root / 'exports')])
        endpoint = 'http://127.0.0.1:' + str(args.port)
        deadline = time.monotonic() + 90
        while True:
            logs = run(['journalctl', '-u', app_unit, '--no-pager', '-n', '30', '-o', 'cat'])
            if 'Synthetic acceptance services are ready' in logs:
                break
            assert time.monotonic() < deadline, 'disposable application did not become ready'
            time.sleep(.5)
        with urllib.request.urlopen(endpoint + '/actuator/health', timeout=5) as response:
            assert response.status == 200
        receipt = root / 'client.json'
        receipt.write_text(json.dumps({'endpoint': endpoint, 'password': password, 'root': str(root)}))
        receipt.chmod(0o600)
        print(json.dumps({'ready': True, 'root': str(root), 'endpoint': endpoint,
                          'runtimeManifestSha256': sha(runtime / 'manifest.sha256'),
                          'workerSources': {name: sha(root / name) for name in MODULES}}), flush=True)
        deadline = time.monotonic() + args.lifetime_seconds
        while not (root / 'stop').exists() and time.monotonic() < deadline:
            assert run(['systemctl', 'is-active', app_unit]) == 'active'
            time.sleep(1)
    finally:
        failures = []

        def attempt(label, action):
            try:
                action()
            except Exception as error:
                failures.append(label + ': ' + type(error).__name__)

        for unit in (app_unit, worker_unit):
            attempt('stop ' + unit, lambda unit=unit: run(['systemctl', 'stop', unit]))
            attempt('reset ' + unit, lambda unit=unit: subprocess.run(
                ['systemctl', 'reset-failed', unit], capture_output=True, timeout=10))
        if worker_config is not None:
            attempt('worker cleanup', lambda: run([
                sys.executable, str(root / 'worker.py'), '--config', str(worker_config), '--cleanup']))
        # Stop the owned slice even when another component failed. Never remove a
        # mounted fixture or an unverified container just to report successful cleanup.
        attempt('resource pool cleanup', pool.close)
        if db_attempted:
            def remove_database():
                if run(['docker', 'inspect', '--format', '{{index .Config.Labels "poketto.acceptance"}}', db]) != token:
                    raise RuntimeError('Database ownership differs; refusing removal')
                run(['docker', 'rm', '-f', '-v', db])
            attempt('database cleanup', remove_database)
        for user in reversed(users):
            def remove_user(user=user):
                result = subprocess.run(['pgrep', '-u', str(pwd.getpwnam(user).pw_uid)], capture_output=True, timeout=5)
                if result.returncode != 1:
                    raise RuntimeError('Account still has processes or process lookup failed')
                run(['userdel', user])
            attempt('account cleanup ' + user, remove_user)
        def remove_root():
            mounts = run(['findmnt', '-rn', '-o', 'TARGET']).split('\n')
            if any(value == str(root) or value.startswith(str(root) + '/') for value in mounts):
                raise RuntimeError('Fixture still contains mounts')
            if root.parent != Path('/var/lib') or not root.name.startswith('poketto-client-'):
                raise RuntimeError('Unexpected fixture path')
            shutil.rmtree(root)
        if not failures:
            attempt('fixture cleanup', remove_root)
        if failures:
            attempt('restrict retained fixture', lambda: root.chmod(0o700))
            print(json.dumps({'cleanup': 'FAILED', 'failures': failures}), flush=True)
            raise RuntimeError('Incomplete isolated fixture cleanup; root-only evidence retained')
        print(json.dumps({'cleanup': 'PASS'}), flush=True)



if __name__ == '__main__':
    main()
