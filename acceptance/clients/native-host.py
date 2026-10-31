#!/usr/bin/env python3
"""Hold disposable Spring/PostgreSQL/Sandcrab services for real MCP clients on Linux."""
import argparse
import json
import os
from pathlib import Path
import pwd
import secrets
import shutil
import signal
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import uuid
from native_repository_fixture import (COPY_BYTES, COPY_INODES, POOL_BYTES, TRANSFER_BYTES,
                                       NativeRepositoryFixture, sha, stop_unit, verify_runtime)
from native_app_fixture import OwnedApplication

POSTGRES = 'postgres:17.11-bookworm@sha256:051f7b7b3abdd564d5d1bd1e8c4b9c1b6e77087d1dd22020ede611c096a272e0'
REPOSITORY = Path(__file__).resolve().parents[2]


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=60, **kwargs).stdout.strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'binary', 'tools', 'java'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--port', type=int, default=38189)
    parser.add_argument('--lifetime-seconds', type=int, default=1800)
    parser.add_argument('--games', action='store_true')
    parser.add_argument('--retained-process', action='store_true', help='Allow two identity-bound restarts of this fixture app')
    parser.add_argument('--agents', type=int, default=4, help='concurrent agents the services admit')
    parser.add_argument('--origin', help='Browser gateway origin; defaults to the API loopback origin')
    args = parser.parse_args()
    assert os.geteuid() == 0 and 1024 <= args.port <= 65535 and 60 <= args.lifetime_seconds <= 3600
    assert 1 <= args.agents <= 64
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Disposable native consumer fixture interrupted')
    signal.signal(signal.SIGTERM, interrupted)
    runtime, binary, tools, java = [p.resolve(strict=True) for p in
                                  (args.runtime, args.binary, args.tools, args.java)]
    verify_runtime(runtime)
    with socket.socket() as port_check:
        port_check.bind(('127.0.0.1', args.port))
    sys.path.insert(0, str(REPOSITORY / 'executor-service'))
    from native_pool import NativePool
    token = uuid.uuid4().hex[:8]
    # This is the fixture's resource ceiling, not a measured capacity guarantee.
    pool = NativePool(token, f'{max(512, 128 * args.agents)}M', max(256, 48 * args.agents))
    root = Path(tempfile.mkdtemp(prefix='poketto-client-', dir='/var/lib'))
    root.chmod(0o751)
    app_user, exec_user = 'pkt-capp-' + token, 'pkt-cexec-' + token
    app_unit, db = ['poketto-client-' + token + '-' + name for name in ('app', 'db')]
    users, db_attempted, native = [], False, None
    disk_pool, disk_mounted = root / "pool", False
    exports = disk_pool / "exports"
    game_fixture, application = None, None
    try:
        pool.start()
        for user in (app_user, exec_user):
            run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
            users.append(user)
        app_account = pwd.getpwnam(app_user)
        disk_pool.mkdir()
        image = root / 'copies.img'
        run(['fallocate', '-l', '512M', str(image)])
        run(['mkfs.xfs', '-f', str(image)])
        run(['mount', '-o', 'loop,prjquota,nosuid,nodev', str(image), str(disk_pool)])
        disk_mounted = True
        for path in (exports, disk_pool / 'metadata', disk_pool / 'transfers', root / 'content', root / 'home'):
            path.mkdir(mode=0o700)
            os.chown(path, app_account.pw_uid, app_account.pw_gid)
        native = NativeRepositoryFixture(root, binary, tools, app_account, exec_user, pool.name, token, args.agents)
        native_info = native.start()
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
        game_settings = {}
        if args.games:
            sys.path.insert(0, str(REPOSITORY / 'executor-native'))
            from game_fixture import GameFixture
            game_source = root / 'game-source'
            game_source.mkdir(mode=0o755)
            shutil.copyfile(binary, game_source / 'sandcrab')
            shutil.copyfile(REPOSITORY / 'examples/pocket-game/rules.mjs', game_source / 'game-example.mjs')
            game_fixture = GameFixture(root, game_source, tools, app_account, exec_user, pool.name)
            game_settings = game_fixture.start()
        app_environment.write_text('\n'.join([
            'SPRING_DATASOURCE_URL=jdbc:postgresql://' + binding + '/acceptance',
            'SPRING_DATASOURCE_USERNAME=acceptance', 'SPRING_DATASOURCE_PASSWORD=' + password,
            'POKETTO_ACCEPTANCE_ROOT=' + str(root / 'content'), 'POKETTO_ACCEPTANCE_PASSWORD=' + password,
            'POKETTO_ACCEPTANCE_ORIGIN=' + (args.origin or 'http://127.0.0.1:' + str(args.port)),
            'POKETTO_PLAZA_PUBLIC_URL=' + (args.origin or 'http://127.0.0.1:' + str(args.port)),
            'POKETTO_ACCEPTANCE_GAME_PACKAGE=' + (str(runtime / 'game-example') if args.games else ''),
            'POKETTO_SESSION_COOKIE_SECURE=false', 'HOME=' + str(root / 'home')]) + '\n')
        if args.retained_process:
            with app_environment.open('a') as environment_file:
                environment_file.write('POKETTO_ACCEPTANCE_RETAINED_IDENTITY=' + uuid.uuid4().hex + '\n')
        app_environment.chmod(0o600)
        copy_args = [
            '--poketto.executor.copies.metadata-root=' + str(disk_pool / 'metadata'),
            '--poketto.executor.copies.pool-bytes=' + str(POOL_BYTES),
            '--poketto.executor.copies.bytes=' + str(COPY_BYTES),
            '--poketto.executor.copies.inodes=' + str(COPY_INODES),
            '--poketto.executor.profile=repository',
            '--poketto.executor.transfers.directory=' + str(disk_pool / 'transfers'),
            '--poketto.executor.transfers.max-bytes=' + str(TRANSFER_BYTES),
            '--poketto.executor.copies.original-bytes=67108864',
            '--poketto.executor.copies.original-expanded-bytes=268435456',
            '--poketto.executor.max-sessions=' + str(max(4, args.agents)),
            '--poketto.executor.max-operations=' + str(max(4, args.agents))]
        if args.games:
            copy_args.extend(['--poketto.games.enabled=true', '--poketto.games.max-jobs=1',
                '--poketto.games.socket=' + game_settings['gameSocket']])
        lifetime_deadline = time.monotonic() + args.lifetime_seconds
        app_arguments = ['-p', 'User=' + app_user,
             '-p', 'EnvironmentFile=' + str(app_environment), '-p', 'UMask=0077',
             '-p', 'MemoryMax=768M', '-p', 'MemorySwapMax=0', '-p', 'TasksMax=256', '-p', 'CPUQuota=100%',
             '-p', 'TimeoutStopSec=30', '-p', 'KillMode=control-group',
             str(java), '-Xmx384m', '-XX:MaxMetaspaceSize=192m', '-Duser.home=' + str(root / 'home'),
             '-Djava.awt.headless=true', '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars/*'),
             'io.github.core607.poketto.acceptance.AcceptanceApplication',
             '--server.address=127.0.0.1', '--server.port=' + str(args.port),
             '--poketto.executor.enabled=true', '--poketto.executor.socket=' + str(native.socket),
             '--poketto.executor.staging-directory=' + str(exports)] + copy_args
        endpoint = 'http://127.0.0.1:' + str(args.port)
        application = OwnedApplication(root, app_unit, app_arguments, endpoint, lifetime_deadline, args.retained_process)
        try:
            application.start()
        except urllib.error.HTTPError as error:
            health = json.loads(error.read(65536))
            print(json.dumps({'nativeHealth': {'status': health.get('status'),
                'components': {name: component.get('status')
                               for name, component in health.get('components', {}).items()}}}), flush=True)
            # Preserve the native startup failure before removing the fixture.
            # App and database environments contain temporary credentials and are never dumped.
            print(json.dumps({'nativeServiceStartup': run([
                'journalctl', '-u', native.unit, '--no-pager', '-n', '40', '-o', 'cat'])}), flush=True)
            raise
        receipt = root / 'client.json'
        receipt.write_text(json.dumps({'endpoint': endpoint, 'password': password, 'root': str(root),
                                     'nativeBinary': str(native.binary), 'nativeSocket': str(native.socket),
                                     'nativeEpoch': native_info['serverId'], 'appUser': app_user,
                                     'appIdentity': application.identity,
                                     'appControl': str(application.control) if application.control else None,
                                     'nativeUnit': native.unit,
                                     'nativeSlice': pool.name,
                                     'gameUnitPrefix': game_fixture.prefix if game_fixture is not None else None}))
        receipt.chmod(0o600)
        print(json.dumps({'ready': True, 'root': str(root), 'endpoint': endpoint,
                          'accountDiskCopies': True, 'backend': 'sandcrab-native-v1', 'controllerSha256': sha(Path(__file__)),
                          'runtimeManifestSha256': sha(runtime / 'manifest.sha256'),
                          'binarySha256': sha(native.binary), 'nativeConfigSha256': sha(native.config),
                          'fixtureSha256': sha(Path(__file__).with_name('native_repository_fixture.py'))}), flush=True)
        while not (root / 'stop').exists() and time.monotonic() < lifetime_deadline:
            application.poll()
            time.sleep(1)
    finally:
        failures = []

        def attempt(label, action):
            try:
                action()
            except Exception as error:
                failures.append(label + ': ' + type(error).__name__)

        attempt('application containment', application.close if application else lambda: stop_unit(app_unit))
        if native is not None:
            attempt('native service containment', native.stop)
        if game_fixture is not None:
            attempt('game service cleanup', game_fixture.close)
        # Stop the owned slice even when another component failed. Never remove a
        # mounted fixture or an unverified container just to report successful cleanup.
        attempt('resource pool cleanup', pool.close)
        if native is not None:
            attempt('native volume unmount', native.unmount)
        if disk_mounted:
            attempt('copy pool unmount', lambda: run(['umount', str(disk_pool)]))
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
