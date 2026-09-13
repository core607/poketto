"""Private, immutable, checksummed worker checkpoints on persistent host storage."""
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import struct
import time
import uuid

from checkpoint_tree import CheckpointError, directory, exact, read_tree, write_tree


MAGIC = b'PKWC0001'
NAME = re.compile(r'[0-9a-f]{64}_[0-9a-f-]{36}\.checkpoint')


def canonical_id(value):
    try:
        return str(uuid.UUID(value)) == value
    except (ValueError, TypeError, AttributeError):
        return False


def metadata_valid(value):
    keys = {'format', 'checkpointId', 'principalId', 'accountId', 'workspaceId',
            'sourceLeaseId', 'commit', 'scope', 'expiresAt'}
    return (isinstance(value, dict) and set(value) == keys and type(value['format']) is int and
            value['format'] == 1 and all(canonical_id(value[key]) for key in
            ('checkpointId', 'principalId', 'accountId', 'workspaceId', 'sourceLeaseId')) and
            isinstance(value['commit'], str) and re.fullmatch('[0-9a-f]{40}', value['commit']) and
            value['scope'] in ('full', 'public') and type(value['expiresAt']) is int and
            0 < value['expiresAt'] <= 9_007_199_254_740_991)


class BoundedOutput:
    def __init__(self, file, limit, available, reserve):
        self.file = file
        self.limit = min(limit, available)
        self.reserve = reserve
        self.size = 0
        self.digest = hashlib.sha256()

    def write(self, value):
        size = self.size + len(value)
        space = os.fstatvfs(self.file.fileno())
        if size > self.limit or space.f_bavail * space.f_frsize - len(value) < self.reserve:
            raise CheckpointError('CAPACITY')
        view = memoryview(value)
        while view:
            written = self.file.write(view)
            if not written:
                raise OSError('Checkpoint write made no progress')
            view = view[written:]
        self.digest.update(value)
        self.size = size


class CheckpointStore:
    def __init__(self, root, limits, clock=time.time):
        self.root = Path(root)
        self.limits = limits
        self.clock = clock
        if not self.root.is_absolute() or '..' in self.root.parts:
            raise ValueError('Checkpoint root must be absolute')
        for path in reversed(self.root.parents):
            info = path.lstat()
            sticky = info.st_mode & stat.S_ISVTX and info.st_uid == 0
            if (not stat.S_ISDIR(info.st_mode) or info.st_uid not in (0, os.geteuid()) or
                    info.st_mode & 0o022 and not sticky):
                raise ValueError('Unsafe checkpoint ancestor')
        self.root.mkdir(mode=0o700, exist_ok=True)
        info = self.root.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) != 0o700:
            raise ValueError('Checkpoint root must be private and supervisor-owned')

    def filename(self, owner, checkpoint_id):
        if len(owner) != 3 or not all(canonical_id(value) for value in (*owner, checkpoint_id)):
            raise CheckpointError('INVALID_REQUEST')
        digest = hashlib.sha256(':'.join(owner).encode()).hexdigest()
        return digest + '_' + checkpoint_id + '.checkpoint'

    @contextmanager
    def locked(self):
        root_fd = directory(None, self.root)
        lock_fd = None
        try:
            lock_fd = os.open('.lock', os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK,
                              0o600, dir_fd=root_fd)
            self.private_file(os.fstat(lock_fd))
            try:
                fcntl.flock(lock_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError as error:
                raise CheckpointError('BUSY') from error
            yield root_fd
        finally:
            if lock_fd is not None:
                os.close(lock_fd)
            os.close(root_fd)

    @staticmethod
    def private_file(info):
        if (not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or
                stat.S_IMODE(info.st_mode) != 0o600 or info.st_nlink != 1):
            raise CheckpointError('UNAVAILABLE')

    def usage(self, root_fd):
        total = count = scanned = 0
        with os.scandir(root_fd) as entries:
            for entry in entries:
                scanned += 1
                if scanned > self.limits['maxCheckpoints'] * 2 + 16:
                    raise CheckpointError('CAPACITY')
                if entry.name == '.lock':
                    continue
                if entry.name.startswith('.pending-') and canonical_id(entry.name[9:]):
                    self.private_file(entry.stat(follow_symlinks=False))
                    os.unlink(entry.name, dir_fd=root_fd)
                    continue
                if not NAME.fullmatch(entry.name):
                    raise CheckpointError('UNAVAILABLE')
                info = entry.stat(follow_symlinks=False)
                self.private_file(info)
                total += info.st_size
                count += 1
                if count >= self.limits['maxCheckpoints'] or total >= self.limits['maxRetainedBytes']:
                    raise CheckpointError('CAPACITY')
        return self.limits['maxRetainedBytes'] - total

    def capture(self, source, metadata, cancelled=lambda: False):
        if not metadata_valid(metadata):
            raise CheckpointError('INVALID_REQUEST')
        expires = metadata['expiresAt'] / 1000
        if not self.clock() < expires <= self.clock() + self.limits['retentionSeconds']:
            raise CheckpointError('EXPIRED')
        owner = tuple(metadata[key] for key in ('principalId', 'accountId', 'workspaceId'))
        name = self.filename(owner, metadata['checkpointId'])
        temporary = '.pending-' + str(uuid.uuid4())
        published = False
        with self.locked() as root_fd:
            try:
                try:
                    os.stat(name, dir_fd=root_fd, follow_symlinks=False)
                except FileNotFoundError:
                    pass
                else:
                    raise CheckpointError('ALREADY_EXISTS')
                available = self.usage(root_fd)
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                             0o600, dir_fd=root_fd)
                with os.fdopen(fd, 'wb', buffering=0) as file:
                    out = BoundedOutput(file, self.limits['maxCheckpointBytes'], available,
                                        self.limits['minimumFreeBytes'])
                    raw = json.dumps(metadata, separators=(',', ':')).encode()
                    out.write(MAGIC + struct.pack('>I', len(raw)) + raw)
                    write_tree(source, out, self.limits['maxCheckpointEntries'], cancelled)
                    if cancelled():
                        raise CheckpointError('CANCELLED')
                    os.fsync(file.fileno())
                os.rename(temporary, name, src_dir_fd=root_fd, dst_dir_fd=root_fd)
                published = True
                os.fsync(root_fd)
                return {'checkpointId': metadata['checkpointId'], 'sha256': out.digest.hexdigest(),
                        'bytes': out.size, 'expiresAt': metadata['expiresAt']}
            except OSError as error:
                raise CheckpointError('UNCERTAIN' if published else 'UNAVAILABLE') from error
            finally:
                if not published:
                    try:
                        os.unlink(temporary, dir_fd=root_fd)
                    except FileNotFoundError:
                        pass

    @contextmanager
    def verified(self, root_fd, owner, reference):
        name = self.filename(owner, reference['checkpointId'])
        if (type(reference['bytes']) is not int or not 1 <= reference['bytes'] <= self.limits['maxCheckpointBytes'] or
                not isinstance(reference['sha256'], str) or not re.fullmatch('[0-9a-f]{64}', reference['sha256'])):
            raise CheckpointError('INVALID_REQUEST')
        try:
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=root_fd)
        except FileNotFoundError as error:
            raise CheckpointError('MISSING') from error
        with os.fdopen(fd, 'rb') as file:
            info = os.fstat(file.fileno())
            self.private_file(info)
            if info.st_size != reference['bytes']:
                raise CheckpointError('CORRUPT')
            digest = hashlib.sha256()
            while part := file.read(65536):
                digest.update(part)
            if digest.hexdigest() != reference['sha256']:
                raise CheckpointError('CORRUPT')
            file.seek(0)
            if exact(file, len(MAGIC)) != MAGIC:
                raise CheckpointError('CORRUPT')
            length, = struct.unpack('>I', exact(file, 4))
            if not 1 <= length <= 2048:
                raise CheckpointError('CORRUPT')
            try:
                metadata = json.loads(exact(file, length))
            except (ValueError, UnicodeError) as error:
                raise CheckpointError('CORRUPT') from error
            if not metadata_valid(metadata):
                raise CheckpointError('CORRUPT')
            if (tuple(metadata[key] for key in ('principalId', 'accountId', 'workspaceId')) != tuple(owner) or
                    metadata['checkpointId'] != reference['checkpointId']):
                raise CheckpointError('MISSING')
            yield file, metadata

    def inspect(self, owner, reference):
        with self.locked() as root_fd, self.verified(root_fd, owner, reference) as (_, metadata):
            self.unexpired(metadata)
            return metadata

    def restore(self, owner, reference, target, commit, scope, uid, gid, cancelled=lambda: False):
        with self.locked() as root_fd, self.verified(root_fd, owner, reference) as (file, metadata):
            self.unexpired(metadata)
            if metadata['commit'] != commit or metadata['scope'] != scope:
                raise CheckpointError('SCOPE_MISMATCH')
            read_tree(file, target, self.limits['maxCheckpointEntries'], self.limits['maxCheckpointBytes'],
                      uid, gid, cancelled)
            return metadata

    def discard(self, owner, reference):
        with self.locked() as root_fd, self.verified(root_fd, owner, reference):
            os.unlink(self.filename(owner, reference['checkpointId']), dir_fd=root_fd)
            os.fsync(root_fd)

    def unexpired(self, metadata):
        if metadata['expiresAt'] <= self.clock() * 1000:
            raise CheckpointError('EXPIRED')
