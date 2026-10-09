"""Independent disposable game worker beside the native repository fixture."""
import json
import os
from pathlib import Path
import pwd
import shutil
import socketserver
import subprocess
import threading
import time
import uuid
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat, PrivateFormat, NoEncryption
from native_pool import NativePool


def run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30).stdout.strip()


class GameFixture:
    def __init__(self, root, source, tools, app, repository_user, repository_slice):
        self.root, self.tools, self.app = root, tools, app
        self.token = uuid.uuid4().hex[:8]
        self.user, self.supervisor = 'pkt-game-' + self.token, 'poketto-game-probe-' + self.token
        self.pool = NativePool(self.token, memory='384M')
        self.created_user = False
        self.config_path = root / 'game-config.json'
        self.runtime = root / 'game-runtime'
        self.accepted = 0
        fixture = self
        class Listener(socketserver.BaseRequestHandler):
            def handle(self):
                fixture.accepted += 1
        self.listener = socketserver.TCPServer(('127.0.0.1', 0), Listener)
        threading.Thread(target=self.listener.serve_forever, daemon=True).start()
        for name in ('game_worker.py', 'game-entry.mjs', 'game-runtime.mjs', 'game-example.mjs'):
            shutil.copy2(source / name, root / name)
            os.chmod(root / name, 0o644)
        self.config = {'runtimeRoot': str(self.runtime), 'socketPath': str(self.runtime / 'control.sock'),
            'publicKey': str(root / 'game-public.pem'), 'toolsRoot': str(tools), 'launcher': str(root / 'launcher.py'),
            'execUser': self.user, 'repositoryExecUser': repository_user,
            'repositoryRuntimeRoot': str(root / 'runtime'), 'repositoryResourceSlice': repository_slice,
            'appUid': app.pw_uid, 'appGid': app.pw_gid, 'unitPrefix': 'poketto-game-' + self.token + '-',
            'supervisorUnit': self.supervisor + '.service', 'resourceSlice': self.pool.name,
            'leaseSeconds': 15, 'renewAfterSeconds': 5, 'maxRequests': 256, 'maxConnections': 8, 'maxSessions': 1,
            'memoryBytes': 134217728, 'tasksMax': 48, 'cpuQuotaPercent': 50,
            'temporaryBytes': 2097152, 'temporaryInodes': 128, 'maxTimeoutMillis': 5000}

    def start(self):
        self.pool.start()
        run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', self.user])
        self.created_user = True
        key = Ed25519PrivateKey.generate()
        private = self.root / 'game-private.pem'
        private.write_bytes(key.private_bytes(Encoding.PEM, PrivateFormat.PKCS8, NoEncryption()))
        private.chmod(0o600)
        os.chown(private, self.app.pw_uid, self.app.pw_gid)
        (self.root / 'game-public.pem').write_bytes(key.public_key().public_bytes(Encoding.PEM, PublicFormat.SubjectPublicKeyInfo))
        sentinel = self.root / 'game-host-readable.txt'
        sentinel.write_text('Synthetic host file outside game grants')
        sentinel.chmod(0o444)
        self.config_path.write_text(json.dumps(self.config))
        (self.root / 'game-worker-entry.py').write_text('''import json, os, subprocess, threading
from pathlib import Path
import game_worker
original = game_worker.GameBackend.run
counter_lock = threading.Lock()
def observed(self, *args):
 with counter_lock:
  counter = Path(__file__).with_name('game-run-count')
  count = int(counter.read_text()) if counter.exists() else 0
  counter.write_text(str(count+1)); counter.chmod(0o600)
 def snapshot():
  value = subprocess.run(['ps','-u',self.c['execUser'],'-o','pid,ppid,nlwp,stat,comm,wchan:24'], capture_output=True, text=True, timeout=3)
  unit = subprocess.run(['systemctl','show',args[0].unit,'-p','TasksCurrent','-p','TasksMax','-p','MemoryCurrent','-p','CPUUsageNSec'], capture_output=True, text=True, timeout=3)
  path = Path(__file__).with_name('game-processes.json')
  path.write_text(json.dumps({'processes':value.stdout,'unit':unit.stdout})); path.chmod(0o600)
 timer = threading.Timer(2, snapshot); timer.daemon=True; timer.start()
 try: result = original(self, *args)
 finally: timer.cancel()
 if result['exitCode'] != 0:
  path = Path(__file__).with_name('game-diagnostic.json')
  path.write_text(json.dumps(result)); path.chmod(0o600)
 return result
game_worker.GameBackend.run = observed
game_worker.main()
''')
        self.start_worker()
        return {'gameSocket': self.config['socketPath'], 'gamePrivateKey': str(private),
                'gameHostFile': str(sentinel), 'gameHostPort': self.listener.server_address[1],
                'gameExample': str(self.root / 'game-example.mjs')}

    def start_worker(self):
        run(['systemd-run', '--quiet', '--unit', self.supervisor, '--slice', self.pool.name,
             '-p', 'User=root', '-p', 'Environment=PYTHONPATH=' + str(self.tools / 'python'), '-p', 'UMask=0077',
             '-p', f'ExecStopPost=/usr/bin/python3 {self.root}/game_worker.py --config {self.config_path} --cleanup',
             '/usr/bin/python3', str(self.root / 'game-worker-entry.py'), '--config', str(self.config_path)])
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if Path(self.config['socketPath']).is_socket():
                return
            time.sleep(.05)
        raise AssertionError('Game worker did not bind its socket')

    def no_processes(self):
        uid = str(pwd.getpwnam(self.user).pw_uid)
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            if subprocess.run(['pgrep', '-u', uid], capture_output=True).returncode == 1:
                return
            time.sleep(.05)
        raise AssertionError('Game account still owns descendants')

    def control(self, operation):
        if operation == 'game-await-running':
            uid = str(pwd.getpwnam(self.user).pw_uid)
            deadline = time.monotonic() + 10
            while time.monotonic() < deadline:
                if subprocess.run(['pgrep', '-u', uid, '-x', 'pkt-game-live'], capture_output=True).returncode == 0:
                    return
                time.sleep(.02)
            raise AssertionError('The actual game process was not observed')
        if operation == 'game-restart-worker':
            run(['systemctl', 'kill', '--kill-who=main', '--signal=KILL', self.supervisor])
            run(['systemctl', 'stop', self.supervisor])
            self.no_processes()
            subprocess.run(['systemctl', 'reset-failed', self.supervisor], capture_output=True, timeout=10)
            Path(self.config['socketPath']).unlink(missing_ok=True)
            self.start_worker()
            return
        if operation != 'game-assert-clean':
            raise AssertionError('Unknown game fixture operation')
        self.no_processes()
        assert self.accepted == 0, 'Game reached the host listener'
        assert not list((self.runtime / 'sessions').iterdir()), 'Game job mount survived completion'

    def close(self):
        self.listener.shutdown()
        self.listener.server_close()
        try:
            diagnostic = self.root / 'game-diagnostic.json'
            if diagnostic.exists():
                result = json.loads(diagnostic.read_text())
                print(json.dumps({'syntheticGameDiagnostic': {'exitCode': result['exitCode'],
                    'reason': result['terminationReason'], 'stderr': result['stderr'][:4000]}}), flush=True)
                processes = self.root / 'game-processes.json'
                if processes.exists():
                    print(json.dumps({'syntheticGameProcesses': json.loads(processes.read_text())}), flush=True)
            subprocess.run(['systemctl', 'stop', self.supervisor], capture_output=True, timeout=25)
            subprocess.run(['systemctl', 'reset-failed', self.supervisor], capture_output=True, timeout=10)
            if self.created_user:
                self.no_processes()
                if self.config_path.exists():
                    run(['/usr/bin/python3', str(self.root / 'game_worker.py'), '--config', str(self.config_path), '--cleanup'])
                sessions = self.runtime / 'sessions'
                assert not sessions.exists() or not list(sessions.iterdir())
                run(['userdel', self.user])
        finally:
            self.pool.close()
