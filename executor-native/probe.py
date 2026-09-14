#!/usr/bin/env python3
"""Root-only disposable Java -> worker -> SRT fixture; no production settings or accounts."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import pwd
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption


def run(args, **kwargs):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=40, **kwargs).stdout.strip()


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--runtime', type=Path, required=True)
    parser.add_argument('--worker-source', type=Path, required=True)
    parser.add_argument('--tools', type=Path, required=True)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--fixture-parent', choices=('/run', '/var/lib'), default='/run')
    parser.add_argument('--scenario', choices=('all', 'exports', 'media', 'retained-process', 'ephemeral-lifecycle'), default='all')
    args = parser.parse_args()
    assert os.geteuid() == 0
    runtime, worker_source, tools, java = [value.resolve(strict=True) for value in
                                         (args.runtime, args.worker_source, args.tools, args.java)]
    assert 'version "26.' in subprocess.run([str(java), '-version'], capture_output=True, text=True, check=True).stderr
    expected_files = set()
    for line in (runtime / 'manifest.sha256').read_text().splitlines():
        expected, relative = line.split('  ', 1)
        expected_files.add(relative)
        target = (runtime / relative).resolve(strict=True)
        assert target.is_relative_to(runtime) and digest(target) == expected
    assert expected_files == {str(path.relative_to(runtime)) for path in runtime.rglob('*')
                              if path.is_file() and path.name != 'manifest.sha256'}
    sys.path.insert(0, str(worker_source))
    from native_pool import NativePool
    from resource_pool import check_service
    token = uuid.uuid4().hex[:8]
    resource_pool = NativePool(token)
    root = Path(tempfile.mkdtemp(prefix='poketto-java-native-', dir=args.fixture_parent))
    os.chmod(root, 0o751)
    app_user, exec_user = 'pkt-japp-' + token, 'pkt-jexec-' + token
    supervisor, fake_unit = 'poketto-java-worker-' + token, 'poketto-java-fake-' + token
    app_unit = 'poketto-java-app-' + token
    created_users = []
    process = None
    config_path = root / 'worker.json'
    worker_config = None
    disk_mounted = False
    disk_pool = root / 'copy-pool'
    evidence = []
    for name in ('worker.py', 'disk_pool.py', 'launcher.py', 'resource_pool.py', 'bridge.py', 'cli.py', 'session_files.py', 'binary_capture.py', 'materialize.py', 'artifacts.py', 'checkpoints.py', 'checkpoint_tree.py'):
        shutil.copy2(worker_source / name, root / name)
        os.chmod(root / name, 0o644)
    (root / 'worker_entry.py').write_text('''import json,os
from pathlib import Path
import worker
original=worker.SystemdBackend.run
def observed(self,session,payload,timeout):
 result=original(self,session,payload,timeout)
 if payload.get('mode')=='initialize' and result['exitCode']!=0:
  path=Path(__file__).with_name('initialization.json')
  path.write_text(json.dumps(result))
  os.chmod(path,0o600)
 return result
worker.SystemdBackend.run=observed
worker.main()
''')

    def passed(name, **values):
        record = {'test': name, 'result': 'PASS', **values}
        evidence.append(record)
        print(json.dumps(record), flush=True)

    def start_worker():
        run(['systemd-run', '--quiet', '--unit', supervisor, '--slice', resource_pool.name,
             '-p', 'User=root',
             '-p', 'Environment=PYTHONPATH=' + str(tools / 'python'),
             '-p', 'UMask=0077',
             '-p', f'ExecStopPost=/usr/bin/python3 {root}/worker.py --config {config_path} --cleanup',
             '/usr/bin/python3', str(root / 'worker_entry.py'), '--config', str(config_path)])
        deadline = time.monotonic() + 10
        hello = '''import json,socket,struct,sys
with socket.socket(socket.AF_UNIX) as connection:
 connection.settimeout(2)
 connection.connect(sys.argv[1])
 payload=b'{"operation":"HELLO","version":1}'
 connection.sendall(struct.pack('>I',len(payload))+payload)
 stream=connection.makefile('rb')
 length,=struct.unpack('>I',stream.read(4))
 assert 0<length<=1048576
 response=json.loads(stream.read(length))
 assert response['ok'] is True and response['version']==1
'''
        while time.monotonic() < deadline:
            # A stopped supervisor can leave its socket inode behind until the new bind.
            if subprocess.run(['runuser', '-u', app_user, '--', '/usr/bin/python3', '-c', hello,
                               str(root / 'runtime/control.sock')], capture_output=True, timeout=3).returncode == 0:
                check_service(supervisor + '.service')
                return
            time.sleep(.1)
        raise AssertionError('Worker did not become ready')

    def no_processes(wait=10):
        deadline = time.monotonic() + wait
        while time.monotonic() < deadline:
            if subprocess.run(['pgrep', '-u', str(exec_account.pw_uid)], capture_output=True).returncode == 1:
                return
            time.sleep(.1)
        raise AssertionError('Execution account still owns processes')

    def control(request):
        operation = request['operation']
        if operation == 'await-descendant':
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline:
                if subprocess.run(['pgrep', '-u', str(exec_account.pw_uid), '-x', 'sleep'], capture_output=True).returncode == 0:
                    return
                time.sleep(.05)
            raise AssertionError('Detached child was not observed')
        elif operation == 'assert-no-processes':
            no_processes()
        elif operation == 'assert-source-unchanged':
            assert before == {str(p.relative_to(source)): digest(p) for p in source.rglob('*') if p.is_file()}
            assert digest(master) == bundle_hash
        elif operation == 'restart-worker':
            run(['systemctl', 'kill', '--kill-who=main', '--signal=KILL', supervisor])
            deadline = time.monotonic() + 15
            while time.monotonic() < deadline and list((root / 'runtime/sessions').iterdir()):
                time.sleep(.1)
            assert not list((root / 'runtime/sessions').iterdir())
            no_processes()
            run(['systemctl', 'stop', supervisor])
            subprocess.run(['systemctl', 'reset-failed', supervisor], capture_output=True, timeout=10)
            start_worker()
        else:
            raise AssertionError('Unknown fixture control operation')

    def execute_java(mode):
        nonlocal process
        # The complete batch includes repeated cold opens and deliberately interrupted restarts.
        timeout_seconds = 360 if mode == 'main' else 240
        agent, = list((runtime / 'jars').glob('byte-buddy-agent-*.jar'))
        retained_process = mode.startswith(('retained-produce-', 'retained-resume-'))
        entrypoint = 'RetainedProcessNativeProbe' if retained_process else 'ExecutorNativeProbe'
        command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', app_unit,
                   '-p', 'User=' + app_user, '-p', 'MemoryMax=402653184', '-p', 'TasksMax=64',
                   '-p', 'RuntimeMaxSec=' + str(timeout_seconds),
                   str(java), '-Xmx128m', '-XX:MaxMetaspaceSize=160m', '-Duser.home=' + str(root / 'home'),
                   '-javaagent:' + str(agent), '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars/*'),
                   'io.github.core607.poketto.executor.internal.' + entrypoint, str(root / 'java.json'), mode]
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        output = []
        killed_pid = None
        def relay():
            for line in process.stdout:
                output.append(line)
                print(line, end='', flush=True)
        reader = threading.Thread(target=relay, daemon=True)
        reader.start()
        deadline = time.monotonic() + timeout_seconds
        while process.poll() is None:
            if time.monotonic() >= deadline:
                raise AssertionError('Java native probe exceeded deadline')
            request_file = root / 'control/request.json'
            if request_file.exists():
                request = json.loads(request_file.read_text())
                assert str(uuid.UUID(request['id'])) == request['id']
                if request['operation'] == 'kill-application':
                    assert mode in ('retained-produce-acknowledged', 'retained-produce-interrupted',
                                    'retained-produce-uncertain', 'retained-produce-beforepublish',
                                    'retained-produce-afterpublish')
                    assert killed_pid is None
                    killed_pid = int(run(['systemctl', 'show', '--value', '-p', 'MainPID', app_unit]))
                    assert killed_pid > 1 and Path(f'/proc/{killed_pid}/exe').resolve(strict=True) == java
                    run(['systemctl', 'kill', '--kill-who=all', '--signal=KILL', app_unit])
                    request_file.unlink()
                    continue
                control(request)
                request_file.unlink()
                response = root / 'control/response.tmp'
                response.write_text(json.dumps({'id': request['id'], 'ok': True}))
                os.chmod(response, 0o600)
                os.chown(response, app_account.pw_uid, app_account.pw_gid)
                response.replace(root / 'control/response.json')
            time.sleep(.02)
        reader.join(timeout=5)
        parsed = [json.loads(line) for line in output if line.startswith('{')]
        if mode.startswith('retained-produce-'):
            scenario = mode.removeprefix('retained-produce-')
            assert killed_pid is not None and process.returncode != 0, 'Producer was not externally killed'
            assert not Path(f'/proc/{killed_pid}').exists(), 'Producer JVM still exists'
            assert any(item.get('retainedLossReady') == scenario for item in parsed)
            passed('retained-producer-externally-killed-' + scenario)
            return
        assert killed_pid is None and process.returncode == 0, 'Java native fixture failed'
        if mode == 'main':
            assert any(item.get('summary') == 'PASS' for item in parsed)
        elif mode == 'exports':
            assert {item.get('test') for item in parsed if item.get('result') == 'PASS'} == {
                'private-cli-export-keeps-originals-and-unsaved-edits-without-changing-authority',
                'public-cli-export-translates-only-host-owned-paths-and-preserves-existing-files'}
        elif mode.startswith('retained-resume-'):
            scenario = mode.removeprefix('retained-resume-')
            assert any(item.get('test') == 'retained-jvm-loss-' + scenario and item.get('result') == 'PASS'
                       for item in parsed)
        elif mode == 'media':
            assert {item.get('test') for item in parsed if item.get('result') == 'PASS'} == {
                'full-scope-media-fetch-retains-historical-originals-and-never-overwrites-local-edits',
                'member-projection-fetch-survives-website-shutdown-without-source-history',
                'public-media-list-ignores-local-index-tampering-and-stops-after-withdrawal'}
        elif mode == 'ephemeral-lifecycle':
            assert {item.get('test') for item in parsed if item.get('result') == 'PASS'} == {
                'timeout-preserves-local-work-and-explicit-discard-allows-a-fresh-copy'}
        elif mode in ('account-state-produce', 'account-state-consume'):
            assert any(item.get('test') == mode and item.get('result') == 'PASS' for item in parsed)
        else:
            assert any(item.get('abandon') == 'READY' for item in parsed)

    try:
        resource_pool.start()
        for user in (app_user, exec_user):
            run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
            created_users.append(user)
        app_account, exec_account = pwd.getpwnam(app_user), pwd.getpwnam(exec_user)
        for name in ('exports', 'control', 'fake-inbox', 'public-fixture', 'home'):
            directory = root / name
            directory.mkdir(mode=0o700)
            os.chown(directory, app_account.pw_uid, app_account.pw_gid)
        key = Ed25519PrivateKey.generate()
        private = root / 'private.pem'
        private.write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        os.chmod(private, 0o600)
        os.chown(private, app_account.pw_uid, app_account.pw_gid)
        (root / 'public.pem').write_bytes(key.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
        source = root / 'source'
        source.mkdir(mode=0o700)
        run(['git', 'init', '-q', str(source)])
        (source / 'article.md').write_text('# Synthetic native article\nsearchable 中文\n')
        run(['git', '-C', str(source), 'add', '.'])
        run(['git', '-C', str(source), '-c', 'user.name=Synthetic', '-c', 'user.email=synthetic@example.invalid', 'commit', '-qm', 'Synthetic native history'])
        commit = run(['git', '-C', str(source), 'rev-parse', 'HEAD'])
        run(['git', '-C', str(source), 'update-ref', 'refs/heads/snapshot', commit])
        master = root / 'master.bundle'
        run(['git', '-C', str(source), 'bundle', 'create', str(master), 'refs/heads/snapshot'])
        os.chmod(master, 0o444)
        bundle_hash = digest(master)
        before = {str(p.relative_to(source)): digest(p) for p in source.rglob('*') if p.is_file()}
        worker_config = {'runtimeRoot': str(root / 'runtime'), 'exportRoot': str(root / 'exports'),
            'socketPath': str(root / 'runtime/control.sock'), 'publicKey': str(root / 'public.pem'),
            'toolsRoot': str(tools), 'launcher': str(root / 'launcher.py'), 'execUser': exec_user,
            'appUid': app_account.pw_uid, 'appGid': app_account.pw_gid,
            'unitPrefix': 'poketto-exec-j' + token + '-', 'supervisorUnit': supervisor + '.service', 'resourceSlice': resource_pool.name,
            'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxRequests': 4096, 'maxConnections': 32,
            'maxExecutionsPerSession': 1000, 'maxSessions': 4, 'maxBundleBytes': 16777216,
            'diskBytes': 33554432, 'diskInodes': 8192, 'temporaryBytes': 8388608, 'temporaryInodes': 1024,
            'memoryBytes': 201326592, 'tasksMax': 48, 'cpuQuotaPercent': 50,
            'maxTimeoutMillis': 30000, 'initTimeoutMillis': 15000,
            'checkpointRoot': str(root / 'checkpoints'), 'maxCheckpoints': 128,
            'maxCheckpointEntries': 8192, 'maxCheckpointBytes': 67108864,
            'maxRetainedBytes': 536870912, 'minimumFreeBytes': 0, 'retentionSeconds': 3600}
        if args.scenario == 'ephemeral-lifecycle':
            assert args.fixture_parent == '/var/lib', 'Disk fixture must not allocate its image in tmpfs'
            disk_pool.mkdir()
            disk_image = root / 'copies.img'
            run(['fallocate', '-l', '512M', str(disk_image)])
            run(['mkfs.xfs', '-f', str(disk_image)])
            run(['mount', '-o', 'loop,prjquota,nosuid,nodev', str(disk_image), str(disk_pool)])
            disk_mounted = True
            worker_config.pop('checkpointRoot')
            worker_config.update(copyRoot=str(disk_pool), poolBytes=512*1024*1024)
            run(['install', '-d', '-m', '700', '-o', app_user, '-g', app_user, str(disk_pool / 'metadata')])
        config_path.write_text(json.dumps(worker_config))
        start_worker()
        fake_source = root / 'fake-peer.py'
        shutil.copyfile(Path(__file__).with_name('rejected_peer.py'), fake_source)
        os.chmod(fake_source, 0o644)
        fake_observation = root / 'fake-inbox/observation.json'
        run(['systemd-run', '--quiet', '--unit', fake_unit, '-p', 'User=' + app_user,
             '/usr/bin/python3', str(fake_source), str(root / 'fake-inbox/fake.sock'), str(fake_observation)])
        deadline = time.monotonic() + 10
        while not (root / 'fake-inbox/fake.sock').exists() and time.monotonic() < deadline:
            time.sleep(.1)
        fake_socket = root / 'runtime/fake.sock'
        (root / 'fake-inbox/fake.sock').rename(fake_socket)
        os.chown(fake_socket, 0, app_account.pw_gid)
        os.chmod(fake_socket, 0o660)
        java_config = root / 'java.json'
        java_config.write_text(json.dumps({'socket': worker_config['socketPath'], 'fakeSocket': str(fake_socket),
            'fakeObservation': str(fake_observation),
            'privateKey': str(private), 'exports': str(root / 'exports'), 'bundle': str(master),
            'publicFixture': str(root / 'public-fixture'),
            'accountMetadata': str(disk_pool / 'metadata'),
            'commit': commit, 'control': str(root / 'control')}))
        os.chmod(java_config, 0o600)
        os.chown(java_config, app_account.pw_uid, app_account.pw_gid)
        if args.scenario == 'retained-process':
            for case in ('acknowledged', 'interrupted', 'uncertain', 'beforepublish', 'afterpublish'):
                execute_java('retained-produce-' + case)
                execute_java('retained-resume-' + case)
        else:
            if args.scenario == 'ephemeral-lifecycle':
                execute_java('account-state-produce')
                execute_java('account-state-consume')
            execute_java('main' if args.scenario == 'all' else args.scenario)
        if args.scenario == 'all':
            expired = (root / 'public-fixture/retained/expired-checkpoint').read_text()
            assert str(uuid.UUID(expired)) == expired
            assert not list(Path(worker_config['checkpointRoot']).glob('*_' + expired + '.checkpoint'))
            passed('expired-checkpoint-reclaimed-by-worker-without-client-removal')
            execute_java('abandon')
        no_processes(wait=22)
        if args.scenario == 'retained-process':
            passed('retained-process-recovery-closes-worker-leases')
        elif args.scenario == 'all':
            passed('java-process-loss-expires-real-worker-lease')
        else:
            passed('selected-scenario-closes-worker-leases')
        control({'operation': 'assert-source-unchanged'})
        print(json.dumps({'nativeCombined': 'PASS', 'runtimeManifestSha256': digest(runtime / 'manifest.sha256'),
            'resourcePoolSha256': digest(root / 'resource_pool.py'),
            'nativePoolSha256': digest(worker_source / 'native_pool.py'),
            'workerSha256': digest(root / 'worker.py'), 'launcherSha256': digest(root / 'launcher.py'),
            'bridgeSha256': digest(root / 'bridge.py'), 'cliSha256': digest(root / 'cli.py'),
            'sessionFilesSha256': digest(root / 'session_files.py'),
            'materializeSha256': digest(root / 'materialize.py'),
            'binaryCaptureSha256': digest(root / 'binary_capture.py'),
            'artifactsSha256': digest(root / 'artifacts.py'),
            'checkpointsSha256': digest(root / 'checkpoints.py'),
            'checkpointTreeSha256': digest(root / 'checkpoint_tree.py'),
            'nativeScriptSha256': digest(Path(__file__)), 'peerObserverSha256': digest(fake_source),
            'source': 'synthetic-only', 'scenario': args.scenario}), flush=True)
    finally:
        try:
            diagnostic = root / 'initialization.json'
            if diagnostic.exists():
                result = json.loads(diagnostic.read_text())
                print(json.dumps({'syntheticInitializationDiagnostic': {'exitCode': result['exitCode'],
                    'reason': result['terminationReason'], 'stderr': result['stderr'][:4000]}}), flush=True)
            if process is not None and process.poll() is None:
                process.kill()
                process.wait(timeout=10)
            for unit in (app_unit, fake_unit, supervisor):
                subprocess.run(['systemctl', 'stop', unit], capture_output=True, timeout=25)
                subprocess.run(['systemctl', 'reset-failed', unit], capture_output=True, timeout=10)
            if worker_config is not None:
                cleanup = subprocess.run([sys.executable, str(root / 'worker.py'), '--config', str(config_path), '--cleanup'], capture_output=True, timeout=30)
                sessions = root / 'runtime/sessions'
                assert not sessions.exists() or not list(sessions.iterdir())
                if disk_mounted:
                    run(['umount', str(disk_pool)])
                    disk_mounted = False
                mounts = run(['findmnt', '-rn', '-o', 'TARGET']).splitlines()
                assert not any(value == str(root) or value.startswith(str(root) + '/') for value in mounts)
            for user in reversed(created_users):
                account = pwd.getpwnam(user)
                assert subprocess.run(['pgrep', '-u', str(account.pw_uid)], capture_output=True).returncode == 1
                run(['userdel', user])
            assert root.parent == Path(args.fixture_parent) and root.name.startswith('poketto-java-native-')
            shutil.rmtree(root)
        finally:
            resource_pool.close()
        print(json.dumps({'cleanup': 'PASS'}), flush=True)


if __name__ == '__main__':
    main()
