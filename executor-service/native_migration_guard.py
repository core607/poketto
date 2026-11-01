"""Read-only host proof for an offline legacy-copy import."""
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import uuid
import hashlib
import sys

from native_migration_files import Deferred, canonical, digest_json, identity, protected, read_json

deployment_sources = Path(__file__).resolve().parent.parent / 'deploy'
if not (Path(__file__).resolve().parent / 'native_service.py').is_file() and deployment_sources.is_dir():
    sys.path.insert(0, str(deployment_sources))
from native_service import DeploymentError, bounded_file, parse_deployment_state


def settled_state_digest(raw):
    try:
        state = parse_deployment_state(raw)
    except DeploymentError as failure:
        raise Deferred('DEPLOYMENT_STATE_INVALID') from failure
    if state['status'] != 'healthy':
        raise Deferred('DEPLOYMENT_RECONCILIATION_REQUIRED')
    return hashlib.sha256(raw).hexdigest()


def checked(*arguments):
    result = subprocess.run(arguments, capture_output=True, text=True, timeout=15)
    if result.returncode or len(result.stdout) > 1024 * 1024:
        raise Deferred('HOST_PROOF_FAILED')
    return result.stdout


def unit(name):
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.@-]{0,160}\.(service|slice)', name):
        raise Deferred('UNIT_IDENTITY')
    output = checked('systemctl', 'show', name, '-p', 'LoadState', '-p', 'ActiveState',
                     '-p', 'MainPID', '-p', 'ControlGroup', '-p', 'Slice', '-p', 'BindsTo')
    return dict(line.split('=', 1) for line in output.splitlines() if '=' in line)


def require_stopped(value, cgroup_root=Path('/sys/fs/cgroup')):
    if value.get('ActiveState') not in ('inactive', 'failed') or value.get('MainPID', '0') != '0':
        raise Deferred('LEGACY_WRITER_ACTIVE')
    group = value.get('ControlGroup')
    if group:
        if not group.startswith('/') or '..' in Path(group).parts:
            raise Deferred('CGROUP_IDENTITY')
        path = cgroup_root / group.lstrip('/')
        if path.exists():
            values = dict(line.split() for line in (path / 'cgroup.events').read_text().splitlines())
            if values.get('populated') != '0':
                raise Deferred('LEGACY_DESCENDANTS_ACTIVE')


class SourceGuard:
    def __init__(self, request, deployment_lock, source_lock):
        self.request = request
        self.deployment_lock = deployment_lock
        self.source_lock = source_lock
        self.installation = canonical(request['installationRoot'])
        self.deployment = read_json(self.installation / 'deployment.json', 65536)
        self.legacy = read_json(Path(request['legacyConfig']), 65536)
        self.config_digest = digest_json([self.deployment, self.legacy])
        self.state_sha = self.deployment_state()
        if self.legacy['appUid'] != request['appUid'] or self.legacy['appGid'] != request['appGid']:
            raise Deferred('APPLICATION_OWNER_MISMATCH')
        self.pool = protected(Path(self.legacy['copyRoot']), directory=True)
        self.source = protected(self.pool / 'copies' / request['copyId'], directory=True)
        self.source_identity = identity(self.source)
        self.record = None

    def bind(self, record):
        expected = self.request['owner']
        if record['owner'] != expected or record['copyId'] != self.request['copyId']:
            raise Deferred('JOURNAL_OWNER_MISMATCH')
        stored = read_json(self.source / '.copy.json', 4096)
        fields = {'copyId': record['copyId'], 'accountId': expected['accountId'],
                  'workspaceId': expected['workspaceId'], 'scope': 'full' if expected['fullRead'] else 'public',
                  'commit': record['state']['originalCommit']}
        if set(stored) != set(fields) | {'projectId'} or any(stored.get(k) != v for k, v in fields.items()):
            raise Deferred('SOURCE_OWNER_MISMATCH')
        if type(stored['projectId']) is not int or not 0 < stored['projectId'] < 2**31:
            raise Deferred('SOURCE_PROJECT_IDENTITY')
        initialized = protected(self.source / '.initialized')
        if initialized.stat().st_size > 16 or initialized.read_bytes() != b'1\n':
            raise Deferred('SOURCE_NOT_INITIALIZED')
        attributes = checked('xfs_io', '-r', '-c', 'stat', str(self.source))
        fields = {key.strip(): value.strip() for line in attributes.splitlines()
                  for key, separator, value in [line.partition('=')] if separator}
        if int(fields.get('fsxattr.projid', '0')) != stored['projectId'] or not int(
                fields.get('fsxattr.xflags', '0').split()[0], 16) & 0x200:
            raise Deferred('SOURCE_PROJECT_IDENTITY')
        self.record = record
        self.require()

    def require(self):
        self.deployment_lock.require_held()
        self.source_lock.require_held()
        if self.deployment_state() != self.state_sha:
            raise Deferred('DEPLOYMENT_STATE_CHANGED')
        if identity(self.source) != self.source_identity:
            raise Deferred('SOURCE_IDENTITY_CHANGED')
        if digest_json([read_json(self.installation / 'deployment.json', 65536),
                        read_json(Path(self.request['legacyConfig']), 65536)]) != self.config_digest:
            raise Deferred('HOST_CONFIGURATION_CHANGED')
        self.stopped_application()
        require_stopped(unit(self.legacy['supervisorUnit']))
        require_stopped(unit(self.legacy['resourceSlice']))
        self.launch_units()
        self.unmounted()
        execution_uid = pwd.getpwnam(self.legacy['execUser']).pw_uid
        for name in ('work', 'home'):
            info = (self.source / name).lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != execution_uid or stat.S_IMODE(info.st_mode) != 0o700:
                raise Deferred('PERSISTENT_ROOT_OWNER')
        repository = self.source / 'work' / 'repository'
        if not stat.S_ISDIR(repository.lstat().st_mode) or not stat.S_ISDIR((repository / '.git').lstat().st_mode):
            raise Deferred('EXTERNAL_GIT_DIRECTORY')
        alternates = repository / '.git' / 'objects' / 'info' / 'alternates'
        if alternates.exists() and alternates.stat().st_size:
            raise Deferred('EXTERNAL_GIT_OBJECTS')

    def deployment_state(self):
        try:
            raw = bounded_file(self.installation / '.deployment' / 'state.json', 16 * 1024 * 1024, private=True)
        except (DeploymentError, OSError) as failure:
            raise Deferred('DEPLOYMENT_STATE_UNAVAILABLE') from failure
        return settled_state_digest(raw)

    def stopped_application(self):
        project = self.deployment['project']
        if not re.fullmatch(r'[a-z0-9][a-z0-9_-]{0,62}', project):
            raise Deferred('INSTALLATION_IDENTITY')
        identifiers = checked('docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + project,
                              '--filter', 'label=com.docker.compose.service=app').split()
        if not 1 <= len(identifiers) <= 8 or any(not re.fullmatch('[0-9a-f]{12,64}', value) for value in identifiers):
            raise Deferred('APPLICATION_INVENTORY')
        for identifier in identifiers:
            running = json.loads(checked('docker', 'inspect', '--format', '{{json .State.Running}}', identifier))
            restarting = json.loads(checked('docker', 'inspect', '--format', '{{json .State.Restarting}}', identifier))
            if running or restarting:
                raise Deferred('APPLICATION_ACTIVE')

    def launch_units(self):
        prefix = self.legacy['unitPrefix']
        if not re.fullmatch(r'poketto-exec-[a-z0-9]+-', prefix):
            raise Deferred('LEGACY_UNIT_PREFIX')
        units = set()
        runtime = protected(Path(self.legacy['runtimeRoot']), directory=True)
        records = protected(runtime / 'records', directory=True)
        files = list(records.glob('*.json'))
        if len(files) > 2048:
            raise Deferred('LAUNCH_RECORD_LIMIT')
        for path in files:
            if path.name.endswith('.sandbox.json'):
                continue
            operation = str(uuid.UUID(path.stem))
            launch = read_json(path, 65536)
            if self.record is not None and launch.get('root') == str(runtime / 'sessions' / self.record['writer']['leaseId']):
                units.add(prefix + operation + '.service')
        for line in checked('systemctl', 'list-units', '--all', '--plain', '--no-legend', prefix + '*.service').splitlines():
            name = line.split()[0]
            if not name.startswith(prefix) or not name.endswith('.service'):
                raise Deferred('LEGACY_UNIT_INVENTORY')
            units.add(name)
        for name in units:
            observed = unit(name)
            require_stopped(observed)
            if observed.get('LoadState') == 'loaded' and (
                    observed.get('Slice') != self.legacy['resourceSlice']
                    or self.legacy['supervisorUnit'] not in observed.get('BindsTo', '').split()):
                raise Deferred('LEGACY_UNIT_CONTAINMENT')

    def unmounted(self):
        mount = json.loads(checked('findmnt', '-J', '-T', str(self.pool), '-o', 'TARGET,FSTYPE,OPTIONS'))['filesystems'][0]
        options = mount['options'].split(',')
        if (mount['target'] != str(self.pool) or mount['fstype'] != 'xfs'
                or not {'prjquota', 'pquota'}.intersection(options) or 'pqnoenforce' in options):
            raise Deferred('SOURCE_POOL_CONTRACT')
        device = self.source.stat().st_dev
        native_device = str(os.major(device)) + ':' + str(os.minor(device))
        source_root = '/' + self.source.relative_to(self.pool).as_posix()
        for line in Path('/proc/self/mountinfo').read_text().splitlines():
            fields = line.split()
            if fields[2] == native_device and (fields[3] == source_root or fields[3].startswith(source_root + '/')):
                raise Deferred('SOURCE_STILL_MOUNTED')
