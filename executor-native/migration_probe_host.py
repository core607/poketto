"""Synthetic legacy ownership with real systemd, Docker, XFS and application-UID journals."""
import json
import os
import pwd
from pathlib import Path
import select
import shutil
import subprocess
import uuid

from native_probe import call

JOURNAL = 'io.github.core607.poketto.executor.internal.NativeMigrationJournalProbe'


def write(path, value, mode=0o600):
    with path.open('x', encoding='utf-8') as output:
        output.write(value if isinstance(value, str) else json.dumps(value))
    path.chmod(mode)


class LegacyFixture:
    def __init__(self, probe, args):
        self.probe, self.args = probe, args
        self.app, self.executor = pwd.getpwnam('daemon'), pwd.getpwnam('nobody')
        self.root = probe.root
        self.token = uuid.uuid4().hex[:8]
        self.pool = self.root / 'legacy-pool'
        self.metadata = self.pool / 'metadata'
        self.copy = str(uuid.uuid4())
        self.source = self.pool / 'copies' / self.copy
        self.runtime = self.root / 'legacy-runtime'
        self.java_runtime = self.root / 'java-runtime'
        self.installation = self.root / 'installation'
        self.operation = self.root / 'operation'
        self.mount = None
        self.request_path = self.root / 'request.json'
        self.project = 'migrationfixture' + self.token
        self.container = self.project + '-app'
        self.slice = 'migrationfixture' + self.token + '.slice'
        self.supervisor = 'migration-fixture-' + self.token + '.service'
        self.prefix = 'poketto-exec-' + self.token + '-'
        self.job = self.prefix + str(uuid.uuid4()) + '.service'
        self.unit_files, self.java_units, self.children = [], [], []
        self.container_created = False
        self.container_attempted = False

    def setup(self):
        self.pool.mkdir(mode=0o755)
        self.pool.chmod(0o755)
        image = self.root / 'legacy.xfs'
        with image.open('xb') as output:
            output.truncate(512 * 1024 * 1024)
        call('mkfs.xfs', '-f', '-m', 'reflink=1', str(image))
        call('mount', '-o', 'loop,prjquota,nosuid,nodev', str(image), str(self.pool))
        self.pool.chmod(0o755)
        self.metadata.mkdir(mode=0o700)
        self.metadata.chmod(0o700)
        os.chown(self.metadata, self.app.pw_uid, self.app.pw_gid)
        shutil.copytree(self.args.runtime, self.java_runtime)
        for path in [self.java_runtime, *self.java_runtime.rglob('*')]:
            path.chmod(0o755 if path.is_dir() else 0o644)
        self.original_commit, self.base_commit = self.seed_git()
        self.before = self.journal('create', commits=(self.original_commit, self.base_commit))
        self.metadata_files = self.metadata_snapshot()
        self.prepare_host_records()
        self.start_sources()

    def seed_git(self):
        repository = self.source / 'work/repository'
        repository.mkdir(parents=True, mode=0o700)
        home = self.source / 'home'
        home.mkdir(mode=0o700)
        (repository / 'private').mkdir()
        original = repository / 'private/original.md'
        original.write_text('immutable original before offline import\n')
        (repository / 'private/deleted.md').write_text('an acknowledged deletion follows')
        environment = {'PATH': '/usr/bin:/bin', 'HOME': '/nonexistent', 'GIT_CONFIG_NOSYSTEM': '1',
                       'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_AUTHOR_DATE': '2026-01-01T00:00:00Z',
                       'GIT_COMMITTER_DATE': '2026-01-01T00:00:00Z'}
        def git(*arguments):
            return subprocess.run(['git', '-C', str(repository), '-c', 'core.hooksPath=/dev/null',
                '-c', 'user.name=Migration fixture', '-c', 'user.email=fixture@example.invalid', *arguments],
                env=environment, check=True, capture_output=True, text=True, timeout=20).stdout.strip()
        git('init', '--quiet', '--initial-branch=main')
        git('add', '.')
        git('commit', '--quiet', '-m', 'Synthetic original baseline')
        original_commit = git('rev-parse', 'HEAD')
        (repository / 'private/advanced.md').write_text('acknowledged text\n')
        (repository / 'private/blob.bin').write_bytes(bytes((0, 1, 255)))
        (repository / 'private/deleted.md').unlink()
        git('add', '-A')
        git('commit', '--quiet', '-m', 'Synthetic advanced baseline')
        base_commit = git('rev-parse', 'HEAD')
        (repository / 'private/staged.md').write_text('index-only bytes')
        git('add', 'private/staged.md')
        (repository / 'private/staged.md').write_text('unsaved working bytes')
        original.write_text('unsaved local edit over the immutable original\n')
        (self.source / 'work/extra.bin').write_bytes(bytes(range(256)))
        os.link(self.source / 'work/extra.bin', self.source / 'work/linked.bin')
        (self.source / 'work/shared-name').write_text('work root owns this name')
        (home / 'shared-name').write_text('home root owns this name')
        (home / '.settings').write_text('retained private home')
        (home / '.settings').chmod(0o700)
        (home / 'settings-link').symlink_to('.settings')
        for path in [self.source / 'work', home, *(self.source / 'work').rglob('*'), *home.rglob('*')]:
            os.chown(path, self.executor.pw_uid, self.executor.pw_gid, follow_symlinks=False)
        (self.source / 'work').chmod(0o700)
        home.chmod(0o700)
        self.source.chmod(0o755)
        (self.pool / 'copies').chmod(0o755)
        call('xfs_quota', '-x', '-c', 'project -s -p ' + str(self.source) + ' 123', str(self.pool))
        call('xfs_quota', '-x', '-c', 'limit -p bhard=8m bsoft=8m ihard=256 isoft=256 123', str(self.pool))
        return original_commit, base_commit

    def journal(self, action, commits=(), wait=True):
        unit = self.probe.name + 'j-' + uuid.uuid4().hex[:8] + '.service'
        self.java_units.append(unit)
        command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
            '--slice', self.probe.slice, '-p', 'User=daemon', '-p', 'Group=' + str(self.app.pw_gid), '-p', 'UMask=0077',
            '-p', 'MemoryMax=192M', '-p', 'MemorySwapMax=0', '-p', 'CPUQuota=50%', '-p', 'TasksMax=64',
            '-p', 'RuntimeMaxSec=90', '-p', 'KillMode=control-group', str(self.args.java),
            '-Xmx64m', '-XX:MaxMetaspaceSize=96m', '-XX:ActiveProcessorCount=1', '-cp',
            str(self.java_runtime / 'classes') + ':' + str(self.java_runtime / 'jars/*'),
            JOURNAL, str(self.metadata), self.copy, action, *commits]
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.children.append(process)
        if not wait:
            assert select.select([process.stdout], [], [], 40)[0], 'Journal lock JVM did not acknowledge its lock'
            raw = process.stdout.readline(1024 * 1024 + 1)
            assert len(raw) <= 1024 * 1024 and json.loads(raw)['copyId'] == self.copy
            return process
        output, error = process.communicate(timeout=60)
        prefix = self.args.report.with_name(self.args.report.stem + '-journal-' + uuid.uuid4().hex[:8])
        prefix.with_suffix('.stdout').write_bytes(output)
        prefix.with_suffix('.stderr').write_bytes(error)
        assert process.returncode == 0, 'Real journal JVM failed; retained logs identify the error'
        assert len(output) <= 1024 * 1024
        return json.loads(output)

    def metadata_snapshot(self):
        import hashlib
        return {path.relative_to(self.metadata).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                for path in sorted(self.metadata.rglob('*')) if path.is_file()}

    def prepare_host_records(self):
        deployment = self.installation / '.deployment'
        deployment.mkdir(parents=True, mode=0o700)
        self.installation.chmod(0o755)
        deployment.chmod(0o700)
        write(self.installation / 'deployment.json', {'project': self.project})
        write(deployment / 'state.json', {'status': 'healthy', 'revision': self.args.revision,
            'configuration': 'b' * 64, 'imageIds': {'app': self.args.container_image, 'frontend': self.args.container_image}})
        write(deployment / 'lock', '')
        (self.runtime / 'records').mkdir(parents=True, mode=0o755)
        self.runtime.chmod(0o755)
        (self.runtime / 'records').chmod(0o755)
        self.mount = self.runtime / 'sessions' / self.before['writer']['leaseId'] / 'work'
        self.mount.mkdir(parents=True)
        operation = self.job.removeprefix(self.prefix).removesuffix('.service')
        write(self.runtime / 'records' / (operation + '.json'), {'root': str(self.mount.parent)})
        write(self.source / '.copy.json', {'copyId': self.copy, 'accountId': self.before['owner']['accountId'],
            'workspaceId': self.before['owner']['workspaceId'], 'scope': 'full',
            'commit': self.original_commit, 'projectId': 123})
        write(self.source / '.initialized', '1\n')
        write(self.source / '.lease.lock', '')
        legacy = self.root / 'legacy.json'
        write(legacy, {'appUid': self.app.pw_uid, 'appGid': self.app.pw_gid, 'execUser': 'nobody', 'copyRoot': str(self.pool),
            'runtimeRoot': str(self.runtime), 'supervisorUnit': self.supervisor,
            'resourceSlice': self.slice, 'unitPrefix': self.prefix})
        self.request = {'operation': str(uuid.uuid4()), 'revision': self.args.revision,
            'installationRoot': str(self.installation), 'legacyConfig': str(legacy),
            'appUid': self.app.pw_uid, 'appGid': self.app.pw_gid,
            'java': str(self.args.java), 'runtime': str(self.java_runtime), 'metadata': str(self.metadata),
            'limits': {'copies': 128, 'recordBytes': 16777216, 'poolBytes': 536870912, 'idleMillis': 604800000,
                       'originalFiles': {'archiveBytes': 67108864, 'expandedBytes': 268435456, 'entries': 100000}},
            'owner': self.before['owner'], 'copyId': self.copy, 'socket': str(self.probe.socket),
            'profile': 'repository', 'volumeBytes': 8388608, 'volumeInodes': 256}
        write(self.request_path, self.request)

    def start_sources(self):
        writer = self.root / 'synthetic-writer.py'
        write(writer, 'from pathlib import Path\nimport sys,time\np=Path(sys.argv[1])\n'
              'while True:\n p.write_text("live synthetic writer\\n")\n time.sleep(.1)\n', 0o644)
        units = {
            self.slice: '[Slice]\nMemoryMax=32M\nMemorySwapMax=0\nTasksMax=32\nCPUQuota=10%\n',
            self.supervisor: '[Service]\nSlice=' + self.slice + '\nExecStart=/bin/sleep 600\nKillMode=control-group\n',
            self.job: '[Unit]\nBindsTo=' + self.supervisor + '\nAfter=' + self.supervisor + '\n[Service]\nSlice=' +
                self.slice + '\nUser=nobody\nExecStart=/usr/bin/python3 ' + str(writer) + ' ' +
                str(self.source / 'work/live-writer') + '\nKillMode=control-group\n'}
        for name, content in units.items():
            path = Path('/run/systemd/system') / name
            write(path, content, 0o644)
            self.unit_files.append(path)
        call('systemctl', 'daemon-reload')
        call('systemctl', 'start', self.supervisor, self.job)
        self.container_attempted = True
        call('docker', 'create', '--pull=never', '--name', self.container, '--network', 'none', '--read-only',
             '--memory', '32m', '--memory-swap', '32m', '--cpus', '.1', '--pids-limit', '16',
             '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges',
             '--label', 'com.docker.compose.project=' + self.project, '--label', 'com.docker.compose.service=app',
             '--label', 'sandcrab.migration.fixture=' + self.token,
             '--entrypoint', '/bin/sleep', self.args.container_image, '600')
        self.container_created = True
        call('docker', 'start', self.container)

    def stop_legacy(self):
        call('systemctl', 'stop', self.job, self.supervisor, self.slice)

    def close(self):
        for process in self.children:
            if process.stdin is not None and not process.stdin.closed:
                process.stdin.close()
        for unit in self.java_units:
            call('systemctl', 'stop', unit, check=False)
        for process in self.children:
            process.wait(timeout=15)
            for stream in (process.stdout, process.stderr):
                if stream is not None and not stream.closed:
                    stream.close()
        if self.unit_files:
            from native_migration_guard import require_stopped, unit
            names = [name for name in (self.job, self.supervisor, self.slice)
                     if Path('/run/systemd/system') / name in self.unit_files]
            call('systemctl', 'stop', *names)
            for name in names:
                require_stopped(unit(name))
            for path in self.unit_files:
                path.unlink()
            call('systemctl', 'daemon-reload')
        if self.container_attempted:
            actual = call('docker', 'inspect', '--format', '{{index .Config.Labels "sandcrab.migration.fixture"}}',
                          self.container, check=False)
            if actual.returncode == 0:
                assert actual.stdout.strip() == self.token, 'Docker fixture ownership changed; refusing cleanup'
                call('docker', 'rm', '-f', '-v', self.container)
            else:
                assert 'No such object' in actual.stderr, 'Docker fixture cleanup remains unconfirmed'
