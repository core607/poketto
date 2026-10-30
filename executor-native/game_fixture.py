"""Dedicated disposable native Sandcrab service for Poketto games."""
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
from native_pool import NativePool


def run(args):
    return subprocess.run(args, check=True, capture_output=True, text=True, timeout=30).stdout.strip()


class GameFixture:
    def __init__(self, root, source, tools, app, repository_user, repository_slice):
        self.root, self.tools, self.app = root, tools, app
        self.token = uuid.uuid4().hex[:8]
        self.user, self.supervisor = 'pkt-game-' + self.token, 'sandcrab-game-probe-' + self.token
        self.pool = NativePool(self.token, memory='384M')
        self.created_user = False
        self.config_path = root / 'game-config.toml'
        self.runtime = root / 'game-runtime'
        self.accepted = 0
        fixture = self
        class Listener(socketserver.BaseRequestHandler):
            def handle(self):
                fixture.accepted += 1
        self.listener = socketserver.TCPServer(('127.0.0.1', 0), Listener)
        threading.Thread(target=self.listener.serve_forever, daemon=True).start()
        assert self.user != repository_user and self.pool.name != repository_slice
        self.assets = root / 'game-assets'
        self.assets.mkdir(mode=0o755)
        shutil.copy2(source / 'sandcrab', root / 'sandcrab')
        (root / 'sandcrab').chmod(0o755)
        for name in ('game-entry.mjs', 'game-runtime.mjs', 'game-example.mjs'):
            shutil.copy2(source / name, self.assets / name)
            (self.assets / name).chmod(0o644)
        self.socket = self.runtime / 'control.sock'
        self.prefix = self.supervisor + '-'
        self.config = f'''[service]
runtime_root = {json.dumps(str(self.runtime))}
socket = {json.dumps(str(self.socket))}
client_uid = {app.pw_uid}
client_gid = {app.pw_gid}
resource_slice = "{self.pool.name}"
supervisor_unit = "{self.supervisor}.service"
unit_prefix = "{self.prefix}"
max_sandboxes = 1
max_records = 128
max_output_bytes = 1048576
max_file_records = 128
max_file_transfers = 1
max_connections = 16
max_event_streams = 4
[profiles.games]
exec_user = "{self.user}"
tools = {json.dumps(str(tools))}
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache", {json.dumps(str(self.assets))}]
memory_bytes = 134217728
temporary_bytes = 2097152
temporary_inodes = 128
tasks_max = 48
cpu_quota_percent = 50
timeout_millis = 5000
session_ttl_millis = 15000
output_limit_bytes = 98304
transfer_bytes = 524288
network = "none"
host_calls = false
'''

    def start(self):
        self.pool.start()
        run(['useradd', '--system', '--no-create-home', '--shell', '/usr/sbin/nologin', self.user])
        self.created_user = True
        private = self.root / 'game-host-credential'
        private.write_bytes(os.urandom(32))
        private.chmod(0o600)
        os.chown(private, self.app.pw_uid, self.app.pw_gid)
        owner = self.root / 'game-owner'
        owner.mkdir(mode=0o700)
        os.chown(owner, self.app.pw_uid, self.app.pw_gid)
        sentinel = self.root / 'game-host-readable.txt'
        sentinel.write_text('Synthetic host file outside game grants')
        sentinel.chmod(0o444)
        self.config_path.write_text(self.config)
        self.config_path.chmod(0o600)
        run([str(self.root / 'sandcrab'), 'check', '--config', str(self.config_path)])
        self.start_worker()
        return {'gameSocket': str(self.socket), 'gameHostCredential': str(private),
                'gameOwnerFile': str(owner / 'owner.lock'), 'gameEntry': str(self.assets / 'game-entry.mjs'),
                'gameHostFile': str(sentinel), 'gameHostPort': self.listener.server_address[1],
                'gameExample': str(self.assets / 'game-example.mjs')}

    def start_worker(self):
        deadline = time.monotonic() + 10
        while run(['systemctl', 'show', self.supervisor, '-p', 'LoadState', '--value']) != 'not-found':
            assert time.monotonic() < deadline, 'previous game fixture unit was not collected'
            time.sleep(.05)
        self.socket.unlink(missing_ok=True)
        supervisor = str(self.root / 'sandcrab')
        run(['systemd-run', '--quiet', '--collect', '--unit', self.supervisor, '--slice', self.pool.name,
             '-p', 'User=root', '-p', 'UMask=0077', '-p', 'MemoryMax=128M', '-p', 'MemorySwapMax=0',
             '-p', 'TasksMax=128', supervisor, 'serve', '--config', str(self.config_path)])
        deadline = time.monotonic() + 25
        while time.monotonic() < deadline:
            if self.socket.is_socket():
                state = json.loads(run(['runuser', '-u', self.app.pw_name, '--', supervisor,
                                        'info', '--socket', str(self.socket)]))
                assert list(state['profiles']) == ['games'] and 'volumes' not in state['capabilities']
                return
            time.sleep(.05)
        raise AssertionError('Native game service did not become ready')

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
            self.start_worker()
            return
        if operation != 'game-assert-clean':
            raise AssertionError('Unknown game fixture operation')
        self.no_processes()
        assert self.accepted == 0, 'Game reached the host listener'
        assert not list((self.runtime / 'jobs').iterdir()), 'Game job mount survived completion'

    def close(self):
        self.listener.shutdown()
        self.listener.server_close()
        try:
            subprocess.run(['systemctl', 'stop', self.supervisor], capture_output=True, timeout=25)
            subprocess.run(['systemctl', 'reset-failed', self.supervisor], capture_output=True, timeout=10)
            if self.created_user:
                self.no_processes()
                jobs = self.runtime / 'jobs'
                mounts = [line.split()[4] for line in Path('/proc/self/mountinfo').read_text().splitlines()
                          if line.split()[4].startswith(str(self.runtime) + '/')]
                for mounted in sorted(mounts, key=len, reverse=True):
                    path = Path(mounted)
                    assert path.parent == jobs and not path.is_symlink()
                    uuid.UUID(path.name)
                    run(['umount', str(path)])
                if jobs.exists():
                    for job in jobs.iterdir():
                        uuid.UUID(job.name)
                        assert job.parent == jobs and not job.is_symlink()
                        shutil.rmtree(job)
                run(['userdel', self.user])
        finally:
            self.pool.close()
