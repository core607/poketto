"""Persistent copy directories bounded by an XFS pool and inherited project quotas."""
from contextlib import contextmanager
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import stat
import struct
import subprocess
import tempfile
import uuid


class DiskPool:
    """The supervisor owns the pool; command processes receive only their copy mount.

    Deployment supplies a dedicated, size-bounded XFS mount with project quota
    enforcement. This class never formats a device, mounts storage or falls back
    to unbounded directories. Project identifiers are durably allocated and never
    reused, including after failed initialization. Untrusted commands must run in
    a noninitial user namespace: Linux then forbids changing project IDs and
    project inheritance even on files owned by the command account.
    """

    def __init__(self, root, maximum_bytes, copy_bytes, copy_inodes):
        self.root = Path(root)
        for value in (maximum_bytes, copy_bytes, copy_inodes):
            if type(value) is not int or value <= 0:
                raise ValueError('Disk pool limits must be positive integers')
        if copy_bytes > maximum_bytes:
            raise ValueError('A copy limit cannot exceed the disk pool limit')
        if (not self.root.is_absolute() or not re.fullmatch(r'/[A-Za-z0-9_./-]+', str(self.root))
                or self.root.is_symlink()
                or self.root.resolve() != self.root):
            raise ValueError('Disk pool must be an absolute, resolved directory')
        info = self.root.stat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Disk pool must be root-owned and not writable by other accounts')
        self.maximum_bytes = maximum_bytes
        self.copy_bytes = copy_bytes
        self.copy_inodes = copy_inodes
        self.verify()
        self.copies = self.root / 'copies'
        self.copies.mkdir(mode=0o750, exist_ok=True)
        info = self.copies.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Copy parent must remain supervisor-owned')
        with self._projects_lock():
            self._collect_unpublished()

    @staticmethod
    def _run(*args):
        return subprocess.run(args, check=True, capture_output=True, text=True, timeout=15).stdout

    def verify(self):
        mounts = json.loads(self._run('findmnt', '-J', '-T', str(self.root), '-o', 'TARGET,FSTYPE,OPTIONS'))
        mount = mounts['filesystems'][0]
        options = set(mount['options'].split(','))
        if (mount['target'] != str(self.root) or mount['fstype'] != 'xfs'
                or not options.intersection({'prjquota', 'pquota'})
                or 'pqnoenforce' in options):
            raise ValueError('Disk pool requires its own XFS mount with enforced project quotas')
        usage = os.statvfs(self.root)
        if usage.f_blocks * usage.f_frsize > self.maximum_bytes:
            raise ValueError('Mounted filesystem exceeds the configured aggregate disk limit')

    def create(self, copy_id, account_id, workspace_id, scope, commit):
        """Allocate a new empty copy with hard limits before making it writable."""
        owner = self._identity(copy_id, account_id, workspace_id, scope, commit)
        self.verify()
        with self._projects_lock():
            self._collect_unpublished()
            project = self._allocate_project()
            destination = self.copies / copy_id
            if destination.exists() or destination.is_symlink():
                raise FileExistsError(destination)
            target = self.copies / ('.creating-' + copy_id)
            target.mkdir(mode=0o750)
            try:
                self._run('xfs_quota', '-x', '-c', f'project -s -p {target} {project}', str(self.root))
                # XFS block quotas use KiB. Round down to preserve the byte ceiling.
                blocks = self.copy_bytes // 1024
                if blocks == 0:
                    raise ValueError('Copy quota must allow at least one KiB')
                self._run('xfs_quota', '-x', '-c',
                          f'limit -p bsoft={blocks}k bhard={blocks}k '
                          f'isoft={self.copy_inodes} ihard={self.copy_inodes} {project}', str(self.root))
                metadata = target / '.copy.json'
                with metadata.open('xb') as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(json.dumps({**owner, 'projectId': project}, sort_keys=True).encode())
                    stream.flush()
                    os.fsync(stream.fileno())
                self._sync(target)
                os.rename(target, destination)
                self._sync(self.copies)
                return destination
            except BaseException:
                # Only unpublished allocation staging can be removed here.
                if target.exists():
                    (target / '.copy.json').unlink(missing_ok=True)
                    target.rmdir()
                raise

    @contextmanager
    def _projects_lock(self):
        lock = os.open(self.root / '.projects.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            info = os.fstat(lock)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
                raise ValueError('Invalid project lock ownership')
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield
        finally:
            os.close(lock)

    def _collect_unpublished(self):
        # The allocation lock excludes live creators. These names are never
        # mounted into a command; published copy identities are never swept.
        for target in self.copies.iterdir():
            if not target.name.startswith('.creating-'):
                continue
            suffix = target.name.removeprefix('.creating-')
            if str(uuid.UUID(suffix)) != suffix:
                raise ValueError('Invalid allocation staging name')
            info = target.lstat()
            if not stat.S_ISDIR(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
                raise ValueError('Invalid allocation staging ownership')
            if not shutil.rmtree.avoids_symlink_attacks:
                raise RuntimeError('Descriptor-based removal is required')
            shutil.rmtree(target)
            self._sync(self.copies)

    def reopen(self, copy_id, account_id, workspace_id, scope, commit):
        """Reattach the original directory only when its protected identity matches.

        Runtime lease release does not call this method or delete the directory.
        The caller serializes attachment and retains current authorization and
        save-state metadata independently of command-modifiable Git state.
        """
        expected = self._identity(copy_id, account_id, workspace_id, scope, commit)
        self.verify()
        target = self.copies / copy_id
        fd = os.open(target, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            info = os.fstat(fd)
            if info.st_uid != 0 or info.st_mode & 0o022:
                raise ValueError('Copy root must remain supervisor-owned')
            record = os.open('.copy.json', os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=fd)
            with os.fdopen(record, 'rb') as stream:
                info = os.fstat(stream.fileno())
                if (not stat.S_ISREG(info.st_mode) or info.st_uid != 0
                        or info.st_mode & 0o077 or info.st_size > 4096):
                    raise ValueError('Invalid copy identity record')
                metadata = json.loads(stream.read(4097))
            if (not isinstance(metadata, dict) or set(metadata) != set(expected) | {'projectId'}
                    or any(metadata[key] != value for key, value in expected.items())):
                raise ValueError('Copy owner, scope or pinned commit does not match')
            project = metadata['projectId']
            if type(project) is not int or not 0 < project < 2**31:
                raise ValueError('Invalid copy project identifier')
            attributes = bytearray(28)
            fcntl.ioctl(fd, 0x801c581f, attributes)  # FS_IOC_FSGETXATTR
            flags, _, _, actual_project, _ = struct.unpack('=5I8x', attributes)
            if actual_project != project or not flags & 0x200:  # FS_XFLAG_PROJINHERIT
                raise ValueError('Copy quota inheritance changed')
            return target
        finally:
            os.close(fd)

    def discard(self, copy_id, account_id, workspace_id, scope, commit):
        """Remove an authorized, quiescent copy; active lease locks refuse deletion."""
        target = self.reopen(copy_id, account_id, workspace_id, scope, commit)
        lock = os.open(target / '.lease.lock', os.O_CREAT | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            # The caller holds the supervisor lock. The copy parent is root-owned;
            # rmtree's directory-fd implementation does not follow child symlinks.
            if not shutil.rmtree.avoids_symlink_attacks:
                raise RuntimeError('Descriptor-based removal is required')
            shutil.rmtree(target)
            self._sync(self.copies)
        finally:
            os.close(lock)

    @staticmethod
    def _identity(copy_id, account_id, workspace_id, scope, commit):
        for value in (copy_id, account_id, workspace_id):
            if not isinstance(value, str) or str(uuid.UUID(value)) != value:
                raise ValueError('Copy and owner identifiers must be canonical UUIDs')
        if scope not in ('full', 'public') or not re.fullmatch('[0-9a-f]{40}', commit):
            raise ValueError('Copy scope and pinned commit are required')
        return {'copyId': copy_id, 'accountId': account_id, 'workspaceId': workspace_id,
                'scope': scope, 'commit': commit}

    def _allocate_project(self):
        counter = self.root / '.next-project'
        try:
            fd = os.open(counter, os.O_RDONLY | os.O_NOFOLLOW)
        except FileNotFoundError:
            value = 1
        else:
            with os.fdopen(fd, 'rb') as stream:
                info = os.fstat(stream.fileno())
                if not stat.S_ISREG(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o077:
                    raise ValueError('Invalid project counter ownership')
                raw = stream.read(32)
                if not raw.isdigit():
                    raise ValueError('Invalid project counter')
                value = int(raw)
        if not 0 < value < 2**31:
            raise ValueError('Project identifiers exhausted')
        fd, name = tempfile.mkstemp(prefix='.project-', dir=self.root)
        temporary = Path(name)
        try:
            with os.fdopen(fd, 'wb') as stream:
                stream.write(str(value + 1).encode('ascii'))
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, counter)
            self._sync(self.root)
        finally:
            temporary.unlink(missing_ok=True)
        return value

    @staticmethod
    def _sync(directory):
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
