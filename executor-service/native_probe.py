#!/usr/bin/env python3
"""Real isolated Linux acceptance probe using synthetic Git history and transient services."""
import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
from pathlib import Path
import pwd
import shlex
import socket
import stat
import struct
import subprocess
import sys
import threading
import time
import uuid

from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from worker import recv_exact
from native_pool import NativePool
from resource_pool import check_service, properties


def run(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30, **kw).stdout.strip()


def b64(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--tools', type=Path)
    parser.add_argument('--baseline-only', action='store_true')
    args = parser.parse_args()
    assert os.geteuid() == 0
    root = args.root.resolve(strict=True)
    token = uuid.uuid4().hex[:8]
    runtime = Path('/run') / ('poketto-executor-probe-' + token)
    assert not runtime.exists(), 'Probe runtime directory must be new'
    user = 'pkt-exec-' + token
    supervisor = 'poketto-probe-' + token
    unit_prefix = 'poketto-exec-' + token + '-'
    resource_pool = NativePool(token)
    key = Ed25519PrivateKey.generate()
    pool = concurrent.futures.ThreadPoolExecutor(max_workers=4)
    heartbeat_stops = []
    created_user = False
    disk_pool = root / 'copy-pool'
    disk_image = root / 'copy-pool.img'
    disk_mounted = False
    copy_ids = {}
    source = root / 'synthetic-source'
    exports = root / 'exports'
    exports.mkdir(mode=0o700)
    source.mkdir(mode=0o700)
    host_canary = root / 'host-canary'
    host_canary.write_text('synthetic-host-secret')
    (root / 'public.pem').write_bytes(key.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
    config = {'runtimeRoot': str(runtime), 'exportRoot': str(exports),
        'socketPath': str(runtime / 'control.sock'), 'publicKey': str(root / 'public.pem'),
        'toolsRoot': str((args.tools or root / 'tools').resolve(strict=True)), 'launcher': str(root / 'launcher.py'),
        'execUser': user, 'appUid': 0, 'appGid': 0, 'unitPrefix': unit_prefix,
        'supervisorUnit': supervisor + '.service', 'resourceSlice': resource_pool.name, 'leaseSeconds': 15, 'renewAfterSeconds': 5,
        'maxRequests': 4096, 'maxConnections': 32, 'maxExecutionsPerSession': 1000, 'maxSessions': 4, 'maxBundleBytes': 16777216,
        'diskBytes': 33554432, 'diskInodes': 8192, 'memoryBytes': 201326592,
        'temporaryBytes': 8388608, 'temporaryInodes': 1024,
        'tasksMax': 48, 'cpuQuotaPercent': 50, 'maxTimeoutMillis': 30000,
        'initTimeoutMillis': 15000, 'copyRoot': str(disk_pool), 'poolBytes': 512 * 1024 * 1024}
    (root / 'config.json').write_text(json.dumps(config))
    config_path = str(root / 'config.json')
    def start():
        run(['systemd-run', '--quiet', '--unit', supervisor, '--slice', resource_pool.name,
             '-p', 'User=root', '-p', 'UMask=0077',
             '-p', 'Environment=PYTHONPATH=' + str(Path(config['toolsRoot']) / 'python'),
             '-p', f'ExecStopPost=/usr/bin/python3 {root}/worker.py --config {config_path} --cleanup',
             '/usr/bin/python3', str(root / 'worker.py'), '--config', config_path])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            try:
                return rpc({'version': 1, 'operation': 'HELLO'})
            except OSError:
                time.sleep(.1)
        raise RuntimeError('Worker startup failed')
    def rpc(envelope):
        with socket.socket(socket.AF_UNIX) as client:
            client.settimeout(40)
            client.connect(config['socketPath'])
            raw = json.dumps(envelope).encode()
            client.sendall(struct.pack('!I', len(raw)) + raw)
            size, = struct.unpack('!I', recv_exact(client, 4))
            return json.loads(recv_exact(client, size))
    def signed(p):
        raw = json.dumps(p).encode()
        return {'payload': b64(raw), 'signature': b64(key.sign(raw))}
    boot = None
    def request(identity, operation, data=None):
        now = int(time.time())
        return {**identity, 'version': 1, 'workerBootId': boot, 'operation': operation,
                'requestId': str(uuid.uuid4()), 'issuedAt': now, 'expiresAt': now + 15, 'data': data or {}}
    def send(identity, operation, data=None):
        return rpc(signed(request(identity, operation, data)))
    def new_session(principal=None):
        identity = {'principalId': principal or str(uuid.uuid4()), 'accountId': str(uuid.uuid4()),
            'workspaceId': workspace, 'serverSessionHash': uuid.uuid4().hex * 2,
            'appBootId': app_boot, 'leaseId': str(uuid.uuid4())}
        stop = threading.Event()
        heartbeat_stops.append(stop)
        def renew():
            while not stop.wait(2):
                try:
                    send(identity, 'RENEW')
                except OSError:
                    return
        threading.Thread(target=renew, daemon=True).start()
        copy_ids[identity['leaseId']] = str(uuid.uuid4())
        answer = send(identity, 'OPEN', {'exportId': export_id, 'bundleSha256': bundle_sha,
                    'bundleBytes': bundle.stat().st_size, 'commit': commit, 'copyId': copy_ids[identity['leaseId']], 'scope': 'full'})
        assert answer.get('ok') and answer['state'] == 'READY', answer
        return identity, stop
    def execute(identity, command, timeout=10000, execution_id=None):
        answer = send(identity, 'EXEC', {'executionId': execution_id or str(uuid.uuid4()), 'commit': commit,
                                      'command': command, 'timeoutMillis': timeout})
        assert answer.get('ok') and 'result' in answer, answer
        return answer['result']
    def attach(identity):
        closed = send(identity, 'CLOSE')
        assert closed.get('ok') and closed['state'] == 'CLOSED', closed
        attached = {**identity, 'leaseId': str(uuid.uuid4()), 'appBootId': str(uuid.uuid4()),
                    'serverSessionHash': uuid.uuid4().hex * 2}
        copy_ids[attached['leaseId']] = copy_ids[identity['leaseId']]
        answer = send(attached, 'ATTACH', {'copyId': copy_ids[attached['leaseId']],
                      'scope': 'full', 'commit': commit})
        assert answer.get('ok') and answer['state'] == 'READY', answer
        return attached
    def python(code):
        return '/usr/bin/python3 -c ' + shlex.quote(code)
    evidence = []
    def passed(name, **data):
        record = {'test': name, 'result': 'PASS', **data}
        evidence.append(record)
        print(json.dumps(record), flush=True)
    def baseline_checks(resumed):
        nonlocal boot
        authority = root / 'baseline-source'
        run(['git', 'clone', '--no-local', '--quiet', str(source), str(authority)])
        (authority / 'article.md').write_text('saved through authority')
        run(['git', '-C', str(authority), 'add', 'article.md'])
        run(['git', '-C', str(authority), '-c', 'user.name=Synthetic', '-c', 'user.email=synthetic@example.invalid',
             'commit', '-qm', 'Acknowledged save'])
        updated = run(['git', '-C', str(authority), 'rev-parse', 'HEAD'])
        run(['git', '-C', str(authority), 'bundle', 'create', str(exports / 'updated.bundle'), 'HEAD'])
        export = str(uuid.uuid4())
        updated_bundle = exports / (export + '.bundle')
        (exports / 'updated.bundle').rename(updated_bundle)
        assert execute(resumed, 'printf "saved through authority" > article.md; printf unsaved > unselected.md')['exitCode'] == 0
        execution = str(uuid.uuid4())
        future = pool.submit(execute, resumed, 'sleep 4; git rev-parse HEAD; git status --porcelain; cat unselected.md', 15000, execution)
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            units = run(['systemctl', 'list-units', '--state=running', '--plain', '--no-legend', unit_prefix + '*'])
            if units:
                break
            time.sleep(.05)
        updated_reply = send(resumed, 'BASELINE', {'executionId': execution, 'exportId': export,
                            'bundleSha256': hashlib.sha256(updated_bundle.read_bytes()).hexdigest(),
                            'bundleBytes': updated_bundle.stat().st_size, 'commit': updated})
        assert updated_reply.get('ok') and updated_reply['gitCommit'] == updated, updated_reply
        result = future.result(timeout=20)
        assert result['exitCode'] == 0 and updated in result['stdout'] and 'unsaved' in result['stdout'], result
        assert 'article.md' not in result['stdout'], result
        resumed = attach(resumed)
        assert updated in execute(resumed, 'git rev-parse HEAD')['stdout']
        passed('authoritative-git-baseline-advances-inside-active-command-preserves-drafts-and-survives-reattach')
        run(['systemctl', 'stop', supervisor])
        config['initTimeoutMillis'] = 100
        (root / 'config.json').write_text(json.dumps(config))
        boot = start()['workerBootId']
        resumed = attach(resumed)
        execution = str(uuid.uuid4())
        future = pool.submit(execute, resumed, 'sleep 3; printf parent-survived', 10000, execution)
        wait_units(1)
        refused = send(resumed, 'BASELINE', {'executionId': execution, 'exportId': export,
                       'bundleSha256': hashlib.sha256(updated_bundle.read_bytes()).hexdigest(),
                       'bundleBytes': updated_bundle.stat().st_size, 'commit': updated})
        assert refused.get('code') == 'BASELINE_UNAVAILABLE', refused
        result = future.result(timeout=15)
        assert result['exitCode'] == 0 and result['stdout'] == 'parent-survived', result
        lease_root = runtime / 'sessions' / resumed['leaseId']
        assert not (lease_root / 'baseline.bundle').exists()
        assert not (lease_root / '.git-baseline.pending').exists()
        assert execute(resumed, 'cat unselected.md')['stdout'] == 'unsaved'
        passed('baseline-helper-timeout-cleans-staging-and-preserves-the-parent-command-and-lease')
        run(['systemctl', 'stop', supervisor])
        config['initTimeoutMillis'] = 15000
        (root / 'config.json').write_text(json.dumps(config))
        boot = start()['workerBootId']
        resumed = attach(resumed)
        execution = str(uuid.uuid4())
        future = pool.submit(execute, resumed, 'sleep 30', 30000, execution)
        wait_units(1)
        update = pool.submit(send, resumed, 'BASELINE', {'executionId': execution, 'exportId': export,
                             'bundleSha256': hashlib.sha256(updated_bundle.read_bytes()).hexdigest(),
                             'bundleBytes': updated_bundle.stat().st_size, 'commit': updated})
        wait_units(2)
        assert send(resumed, 'CLOSE', {'reason': 'cancelled'}).get('ok')
        future.result(timeout=15)
        update.result(timeout=15)
        closed = send(resumed, 'CLOSE')
        assert closed.get('ok') and closed['state'] == 'CLOSED', closed
        assert not (runtime / 'sessions' / resumed['leaseId']).exists()
        passed('parent-cancellation-contains-the-active-baseline-helper-before-releasing-the-copy')
        return resumed

    def wait_units(count):
        deadline = time.monotonic() + 5
        while time.monotonic() < deadline:
            units = run(['systemctl', 'list-units', '--state=running', '--plain', '--no-legend', unit_prefix + '*'])
            if len(units.splitlines()) >= count:
                return
            time.sleep(.02)
        raise AssertionError('Expected active command units were not observed')

    try:
        assert run(['findmnt', '-n', '-o', 'FSTYPE', '-T', str(root)]) != 'tmpfs'
        disk_pool.mkdir()
        run(['fallocate', '-l', '512M', str(disk_image)])
        run(['mkfs.xfs', '-f', str(disk_image)])
        run(['mount', '-o', 'loop,prjquota,nosuid,nodev', str(disk_image), str(disk_pool)])
        disk_mounted = True
        resource_pool.start()
        run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', user])
        created_user = True
        run(['git', 'init', '-q', str(source)])
        (source / 'article.md').write_text('# Synthetic article\nsearchable\n')
        run(['git', '-C', str(source), 'add', '.'])
        run(['git', '-C', str(source), '-c', 'user.name=Synthetic', '-c',
             'user.email=synthetic@example.invalid', 'commit', '-qm', 'Synthetic history'])
        commit = run(['git', '-C', str(source), 'rev-parse', 'HEAD'])
        run(['git', '-C', str(source), 'update-ref', 'refs/heads/snapshot', commit])
        export_id = str(uuid.uuid4())
        bundle = exports / (export_id + '.bundle')
        run(['git', '-C', str(source), 'bundle', 'create', str(bundle), 'refs/heads/snapshot'])
        bundle_sha = hashlib.sha256(bundle.read_bytes()).hexdigest()
        before = {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source.rglob('*') if p.is_file()}
        workspace, app_boot = str(uuid.uuid4()), str(uuid.uuid4())
        boot = start()['workerBootId']
        check_service(supervisor + '.service')
        supervisor_umask = run(['systemctl', 'show', supervisor, '-p', 'UMask', '--value'])
        assert supervisor_umask == '0077'
        started = time.monotonic()
        first, first_stop = new_session()
        if args.baseline_only:
            first_stop.set()
            ended = baseline_checks(first)
            assert send(ended, 'DISCARD', {'copyId': copy_ids[ended['leaseId']], 'scope': 'full', 'commit': commit}).get('ok')
            print(json.dumps({'summary': 'PASS', 'tests': len(evidence), 'scenario': 'baseline-only', 'source': 'synthetic-only'}), flush=True)
            return
        source_inodes = {(p.stat().st_dev, p.stat().st_ino) for p in (source / '.git/objects').rglob('*') if p.is_file()}
        copied_objects = runtime / 'sessions' / first['leaseId'] / 'work/repository/.git/objects'
        assert all((p.stat().st_dev, p.stat().st_ino) not in source_inodes for p in copied_objects.rglob('*') if p.is_file())
        passed('initial-signed-bundle-copy', milliseconds=round((time.monotonic() - started) * 1000, 2),
               bundleBytes=bundle.stat().st_size, supervisorUmask=supervisor_umask)
        active = pool.submit(execute, first, 'sleep 3')
        deadline = time.monotonic() + 2
        while time.monotonic() < deadline:
            units = run(['systemctl', 'list-units', '--state=running', '--plain', '--no-legend', unit_prefix + '*']).splitlines()
            if units:
                break
            time.sleep(.05)
        assert units
        for line in units:
            unit = line.split()[0]
            info = properties(unit)
            assert info['Slice'] == resource_pool.name
            assert info['ControlGroup'].startswith(properties(resource_pool.name)['ControlGroup'] + '/')
        assert active.result(timeout=10)['exitCode'] == 0
        passed('root-supervisor-and-command-share-finite-pool', slice=resource_pool.name)
        timings = []
        for _ in range(20):
            started = time.monotonic()
            result = execute(first, 'git log -1 --format=%s; rg searchable article.md; python3 -c "print(42)"')
            assert result['exitCode'] == 0 and 'Synthetic history' in result['stdout'] and '42' in result['stdout'], result
            timings.append((time.monotonic() - started) * 1000)
        passed('twenty-directory-reuses', count=20, meanMillis=round(sum(timings) / 20, 2))
        result = execute(first, python('from pathlib import Path; import os\n'
            'Path("draft.bin").write_bytes(bytes(range(256))); Path("unsaved.md").write_text("retained draft"); '
            'os.symlink("/etc/shadow", "opaque-link"); raise SystemExit(7)'))
        assert result['exitCode'] == 7 and result['terminationReason'] == 'normal', result
        old_first = first
        first_stop.set()
        first = attach(first)
        result = execute(first, python('from pathlib import Path; import os\n'
            'assert Path("draft.bin").read_bytes()==bytes(range(256)); '
            'assert Path("unsaved.md").read_text()=="retained draft"; '
            'assert os.readlink("opaque-link")=="/etc/shadow"') + ' && git rev-parse HEAD')
        assert result['exitCode'] == 0 and commit in result['stdout'], result
        stale = send(old_first, 'EXEC', {'executionId': str(uuid.uuid4()), 'commit': commit,
                     'command': 'printf stale > stale-marker', 'timeoutMillis': 1000})
        assert not stale.get('ok'), stale
        assert not (runtime / 'sessions' / old_first['leaseId']).exists()
        # Keep the recovered lease alive for the remaining real isolation probes.
        first_stop = threading.Event()
        heartbeat_stops.append(first_stop)
        def renew_attached():
            while not first_stop.wait(2):
                try:
                    send(first, 'RENEW')
                except OSError:
                    return
        threading.Thread(target=renew_attached, daemon=True).start()
        passed('disk-attach-preserves-nonzero-command-work-and-fences-source')
        second, second_stop = new_session(first['principalId'])
        execute(first, 'printf isolated > only-first')
        result = execute(second, 'test ! -e only-first')
        assert result['exitCode'] == 0
        passed('same-key-independent-client-directories')
        retained_file = disk_pool / 'copies' / copy_ids[first['leaseId']] / 'snapshot.bundle'
        denied_paths = [str(host_canary), str(source / 'article.md'),
            str(runtime / 'control.sock'), str(runtime / 'sessions' / first['leaseId'] / 'work/repository/article.md'),
            str(runtime / 'sessions' / second['leaseId'] / 'snapshot.bundle'), str(retained_file), '/etc/shadow']
        result = execute(second, python('from pathlib import Path\n' +
            f'for name in {denied_paths!r}:\n try: Path(name).read_bytes()\n except OSError: pass\n else: raise RuntimeError("outside read succeeded")\nprint("denied")'))
        assert result['exitCode'] == 0 and 'denied' in result['stdout'], result
        passed('host-other-session-and-control-path-denied')
        with socket.socket() as sentinel:
            sentinel.bind(('127.0.0.1', 0))
            sentinel.listen(1)
            port = sentinel.getsockname()[1]
            with socket.create_connection(('127.0.0.1', port)):
                pass
            result = execute(second, python('import socket,urllib.request\n' +
                f'for address in [("1.1.1.1",443),("127.0.0.1",{port})]:\n try: socket.create_connection(address,timeout=1)\n except OSError: pass\n else: raise RuntimeError("network allowed")\n' +
                f'p=urllib.request.ProxyHandler({{"https":"http://127.0.0.1:{port}"}})\ntry: urllib.request.build_opener(p).open("https://example.com",timeout=2)\nexcept OSError: pass\nelse: raise RuntimeError("proxy allowed")\nprint("denied")'))
            assert result['exitCode'] == 0 and 'denied' in result['stdout'], result
        passed('network-and-host-proxy-denied')
        result = execute(second, python('import time\ns=time.monotonic(); c=time.process_time()\nwhile time.monotonic()-s<2: pass\nprint(time.process_time()-c)'))
        assert result['exitCode'] == 0 and float(result['stdout'].strip()) < 1.5, result
        passed('cpu-quota-enforced', processCpuSeconds=float(result['stdout'].strip()))
        result = execute(second, python('import os\nfrom pathlib import Path\n' +
            f'assert os.readlink("/proc/self/ns/pid")!={os.readlink("/proc/self/ns/pid")!r}\n' +
            f'assert not Path("/proc/{os.getpid()}").exists()\n' +
            f'for p in ["/proc/1/root{host_canary}","/proc/self/root{host_canary}","/proc/thread-self/root{host_canary}"]:\n try: Path(p).read_bytes()\n except OSError: pass\n else: raise RuntimeError("host root visible")\nprint("denied")'))
        assert result['exitCode'] == 0 and 'denied' in result['stdout'], result
        passed('host-pid-and-proc-root-denied')
        with socket.socket() as startup_sentinel:
            startup_sentinel.bind(('127.0.0.1', 0))
            startup_sentinel.listen(4)
            startup_port = startup_sentinel.getsockname()[1]
            run(['runuser', '-u', user, '--', 'python3', '-c',
                 f'import socket; socket.create_connection(("127.0.0.1",{startup_port}),timeout=1).close()'])
            connection, _ = startup_sentinel.accept()
            connection.close()
            payload = ('import socket\nfrom pathlib import Path\n'
                'Path("startup-marker").write_text("ran")\n'
                f'try: socket.create_connection(("127.0.0.1",{startup_port}),timeout=1)\n'
                'except OSError: Path("startup-network-denied").write_text("denied")\n')
            malicious_shell = '#!/bin/sh\n' + python(payload) + '\n'
            prepare = ('from pathlib import Path\nimport json,os\n'
                f'payload={malicious_shell!r}\n'
                'p=Path("malicious-startup.sh"); p.write_text(payload); p.chmod(0o755)\n'
                'with Path(".git/config").open("a") as f:\n'
                ' f.write("\\n[core]\\n fsmonitor = "+str(p.resolve())+"\\n pager = "+str(p.resolve())+"\\n[include]\\n path = "+str(Path("malicious-include").resolve())+"\\n")\n'
                'Path("malicious-include").write_text("[core]\\n pager = "+str(p.resolve())+"\\n")\n'
                'Path(".srt-settings.json").write_text(json.dumps({"network":{"allowedDomains":["*"]},"filesystem":{"allowRead":["/"],"allowWrite":["/"]}}))\n'
                'for name in (".bashrc",".bash_profile",".profile"):\n'
                ' Path(name).write_text(payload)\n Path(os.environ["HOME"],name).write_text(payload)\n')
            result = execute(second, python(prepare))
            assert result['exitCode'] == 0, result
            result = execute(second, 'test ! -e startup-marker; test ! -e startup-network-denied; printf trusted-startup')
            assert result['exitCode'] == 0 and result['stdout'] == 'trusted-startup', result
            result = execute(second, 'git status --short >/dev/null; test -e startup-marker; test -e startup-network-denied')
            assert result['exitCode'] == 0, result
            startup_sentinel.settimeout(.2)
            try:
                connection, _ = startup_sentinel.accept()
            except TimeoutError:
                pass
            else:
                connection.close()
                raise AssertionError('Git or environment configuration escaped SRT')
            result = execute(second, 'rm -f startup-marker startup-network-denied .srt-settings.json; git -c core.fsmonitor=false config --unset-all core.fsmonitor')
            assert result['exitCode'] == 0, result
        passed('untrusted-git-and-shell-config-reuse-stays-inside-srt')
        result = execute(second, python('import errno\ntry:\n f=open("fill","wb")\n for i in range(64): f.write(b"x"*1048576)\nexcept OSError as e:\n assert e.errno in (errno.ENOSPC,errno.EDQUOT)\n print("disk denied")\nfinally:\n f.close()\n import os; os.unlink("fill")'))
        assert 'disk denied' in result['stdout'], result
        passed('xfs-project-disk-limit')
        result = execute(second, python('import subprocess\nc=[]\ntry:\n for _ in range(100): c.append(subprocess.Popen(["sleep","2"]))\nexcept OSError:\n print("pids denied")\nfinally:\n for p in c: p.terminate()\n for p in c: p.wait()'))
        assert 'pids denied' in result['stdout'], result
        passed('process-limit')
        result = execute(second, 'rm -rf .git/objects; printf modified > article.md')
        assert result['exitCode'] == 0
        assert before == {str(p.relative_to(source)): hashlib.sha256(p.read_bytes()).hexdigest() for p in source.rglob('*') if p.is_file()}
        passed('source-object-and-content-immutability')
        send(second, 'CLOSE')
        second_stop.set()
        descendant = python('import subprocess,time\nsubprocess.Popen(["sleep","60"],start_new_session=True)\nprint("spawned",flush=True)\ntime.sleep(60)')
        result = execute(first, descendant, 1200)
        assert result['timedOut'] and result['terminationReason'] == 'timeout', result
        first_stop.set()
        passed('timeout-kills-detached-descendants')
        identity, stop = new_session()
        result = execute(identity, python('x=bytearray(512*1024*1024)'))
        assert result['exitCode'] != 0 and result['terminationReason'] == 'resource_limit', result
        stop.set()
        passed('memory-oom-limit')
        identity, stop = new_session()
        result = execute(identity, python('import os\nwhile True: os.write(1,b"x"*8192)'))
        assert result['stdoutTruncated'] and result['terminationReason'] == 'output_limit'
        assert len(result['stdout'].encode()) + len(result['stderr'].encode()) <= 65536
        stop.set()
        passed('bounded-output-stops-command')
        for operation in ('CLOSE', 'REVOKE'):
            identity, stop = new_session()
            future = pool.submit(execute, identity, descendant, 30000)
            time.sleep(1)
            data = {'reason': 'cancelled'} if operation == 'CLOSE' else {'keyIds': [identity['principalId']], 'accountIds': []}
            answer = send(identity, operation, data)
            assert answer['ok'], answer
            result = future.result(timeout=10)
            assert result['terminationReason'] == ('cancelled' if operation == 'CLOSE' else 'revoked'), result
            closed = send(identity, 'CLOSE')
            assert closed.get('ok') and closed['state'] == 'CLOSED', closed
            stop.set()
            passed(operation.lower() + '-interrupts-active-process-tree')
        identity, stop = new_session()
        stop.set()
        future = pool.submit(execute, identity, descendant, 30000)
        result = future.result(timeout=25)
        assert result['terminationReason'] == 'lease_expired', result
        passed('abandoned-client-lease-expires')
        identity, stop = new_session()
        assert execute(identity, 'printf disk-before-interruption > acknowledged-draft')['exitCode'] == 0
        future = pool.submit(execute, identity, descendant, 30000)
        time.sleep(1)
        run(['systemctl', 'kill', '--kill-who=main', '--signal=KILL', supervisor])
        stop.set()
        try:
            future.result(timeout=10)
        except Exception:
            pass
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and list((runtime / 'sessions').iterdir()):
            time.sleep(.1)
        assert not list((runtime / 'sessions').iterdir())
        # Empty mounts can precede completion of ExecStopPost and transient-unit collection.
        run(['systemctl', 'stop', supervisor])
        run(['systemctl', 'reset-failed', supervisor])
        previous_boot = boot
        boot = start()['workerBootId']
        assert boot != previous_boot
        passed('supervisor-sigkill-cleans-and-invalidates-leases')
        resumed = attach(identity)
        result = execute(resumed, 'cat acknowledged-draft; git rev-parse HEAD')
        assert result['exitCode'] == 0 and 'disk-before-interruption' in result['stdout'] and commit in result['stdout'], result
        resumed = baseline_checks(resumed)
        assert send(resumed, 'CLOSE').get('ok')
        removed = send(resumed, 'DISCARD', {'copyId': copy_ids[resumed['leaseId']], 'scope': 'full', 'commit': commit})
        assert removed.get('ok'), removed
        passed('disk-copy-survives-supervisor-sigkill-and-retains-baseline')
        print(json.dumps({'summary': 'PASS', 'tests': len(evidence), 'source': 'synthetic-only',
            'runtimeParent': '/run', 'supervisorUmask': supervisor_umask,
            'resourcePoolSha256': hashlib.sha256((root / 'resource_pool.py').read_bytes()).hexdigest(),
            'nativePoolSha256': hashlib.sha256((root / 'native_pool.py').read_bytes()).hexdigest(),
            'workerSha256': hashlib.sha256((root / 'worker.py').read_bytes()).hexdigest(),
            'probeSha256': hashlib.sha256((root / 'native_probe.py').read_bytes()).hexdigest(),
            'launcherSha256': hashlib.sha256((root / 'launcher.py').read_bytes()).hexdigest()}), flush=True)
    finally:
        try:
            for stop in heartbeat_stops:
                stop.set()
            subprocess.run(['systemctl', 'stop', supervisor], capture_output=True, timeout=20)
            subprocess.run(['systemctl', 'reset-failed', supervisor], capture_output=True, timeout=10)
            subprocess.run([sys.executable, str(root / 'worker.py'), '--config', config_path, '--cleanup'], check=True, timeout=30)
            pool.shutdown(wait=True, cancel_futures=True)
            assert not list((runtime / 'sessions').iterdir())
            if disk_mounted:
                run(['umount', str(disk_pool)])
                disk_mounted = False
            if created_user:
                assert subprocess.run(['pgrep', '-u', str(pwd.getpwnam(user).pw_uid)], capture_output=True).returncode == 1
                run(['userdel', user])
            for name in ('control.sock', '.supervisor.lock'):
                file = runtime / name
                if file.exists():
                    assert not file.is_symlink()
                    assert stat.S_ISSOCK(file.stat().st_mode) if name == 'control.sock' else file.is_file()
                    file.unlink()
            (runtime / 'sessions').rmdir()
            (runtime / 'records').rmdir()
            runtime.rmdir()
        finally:
            resource_pool.close()
        print(json.dumps({'cleanup': 'PASS'}), flush=True)


if __name__ == '__main__':
    main()
