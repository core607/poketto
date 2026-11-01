"""Private operator files, fixed archives, and locks; never follows user directory links."""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import tarfile


class Deferred(RuntimeError):
    """The fixed operation needs explicit operator reconciliation; source stays untouched."""


def canonical(path):
    path = Path(path)
    if not path.is_absolute() or path.resolve() != path:
        raise Deferred('PATH_IDENTITY')
    return path


def protected(path, directory=False, uid=0):
    path = canonical(path)
    info = path.lstat()
    valid = stat.S_ISDIR(info.st_mode) if directory else stat.S_ISREG(info.st_mode) and info.st_nlink == 1
    if not valid or info.st_uid != uid or info.st_mode & 0o022:
        raise Deferred('UNPROTECTED_OPERATOR_INPUT')
    for parent in path.parents:
        item = parent.lstat()
        if item.st_uid not in (0, uid) or not stat.S_ISDIR(item.st_mode):
            raise Deferred('UNPROTECTED_OPERATOR_PARENT')
        if item.st_mode & 0o022 and not item.st_mode & stat.S_ISVTX:
            raise Deferred('WRITABLE_OPERATOR_PARENT')
    return path


def read_json(path, maximum=32 * 1024 * 1024, uid=0):
    protected(path, uid=uid)
    with open(path, 'rb') as stream:
        data = stream.read(maximum + 1)
    if len(data) > maximum:
        raise Deferred('OPERATOR_INPUT_LIMIT')
    return json.loads(data)


def sync(directory):
    fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def write_new(path, value, uid=0, gid=0):
    data = (json.dumps(value, sort_keys=True, separators=(',', ':')) + '\n').encode()
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        os.fchown(fd, uid, gid)
        with os.fdopen(fd, 'wb', closefd=False) as stream:
            stream.write(data)
            stream.flush()
            os.fsync(fd)
    finally:
        os.close(fd)
    sync(Path(path).parent)


def digest_json(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def identity(path):
    info = Path(path).lstat()
    return {'device': info.st_dev, 'inode': info.st_ino}


class HeldLock:
    def __init__(self, path, *, create=False, uid=0):
        self.path = Path(path)
        flags = os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK
        self.fd = os.open(path, flags | (os.O_CREAT if create else 0), 0o600)
        try:
            protected(path, uid=uid)
            info = os.fstat(self.fd)
            if identity(path) != {'device': info.st_dev, 'inode': info.st_ino}:
                raise Deferred('LOCK_IDENTITY_CHANGED')
            fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self.saved = identity(path)
        except BaseException:
            os.close(self.fd)
            raise

    def require_held(self):
        if identity(self.path) != self.saved:
            raise Deferred('LOCK_IDENTITY_CHANGED')
        info = Path('/proc/self/fdinfo/' + str(self.fd)).read_text()
        if not any(line.startswith('lock:') and ' FLOCK ' in line and ' WRITE ' in line for line in info.splitlines()):
            raise Deferred('LOCK_LOST')

    def descriptor(self):
        self.require_held()
        return {'descriptor': self.fd, **self.saved}

    def __enter__(self):
        return self

    def __exit__(self, *unused):
        # Do not LOCK_UN: an inherited descriptor must keep the lock if the controller dies.
        os.close(self.fd)


def inventory(source, maximum_entries=10000, maximum_bytes=1024**3):
    source = canonical(source)
    rows, links, total = [], {}, 0
    for root in ('work', 'home'):
        base = source / root
        if not stat.S_ISDIR(base.lstat().st_mode):
            raise Deferred('MISSING_PERSISTENT_ROOT')
        base_info = base.lstat()
        if os.listxattr(base, follow_symlinks=False) or base_info.st_mode & 0o7000:
            raise Deferred('UNSUPPORTED_SOURCE_ATTRIBUTES')
        rows.append({'path': root, 'kind': 'root', 'mode': stat.S_IMODE(base_info.st_mode),
                     'device': base_info.st_dev, 'inode': base_info.st_ino, 'mtimeNs': base_info.st_mtime_ns})
        pending = [base]
        while pending:
            parent = pending.pop()
            for path in sorted(parent.iterdir()):
                relative = path.relative_to(source).as_posix()
                relative.encode('utf-8', errors='strict')
                info = path.lstat()
                if os.listxattr(path, follow_symlinks=False) or info.st_mode & 0o7000:
                    raise Deferred('UNSUPPORTED_SOURCE_ATTRIBUTES')
                row = {'path': relative, 'mode': stat.S_IMODE(info.st_mode), 'device': info.st_dev,
                       'inode': info.st_ino, 'size': info.st_size, 'mtimeNs': info.st_mtime_ns}
                if info.st_dev != base.stat().st_dev:
                    raise Deferred('PERSISTENT_ROOT_MOUNT')
                if stat.S_ISDIR(info.st_mode):
                    row['kind'] = 'directory'
                    pending.append(path)
                elif stat.S_ISLNK(info.st_mode):
                    row.update(kind='symlink', target=os.readlink(path))
                elif stat.S_ISREG(info.st_mode):
                    row.update(kind='file', sha256=file_digest(path)['sha256'], links=info.st_nlink)
                    total += info.st_size
                    links.setdefault((info.st_dev, info.st_ino), []).append((root, info.st_nlink))
                else:
                    raise Deferred('UNSUPPORTED_SOURCE_NODE')
                rows.append(row)
                if len(rows) > maximum_entries or total > maximum_bytes:
                    raise Deferred('SOURCE_LIMIT')
    for members in links.values():
        if len({root for root, count in members}) != 1 or any(count != len(members) for root, count in members):
            raise Deferred('UNCAPTURED_OR_CROSS_ROOT_HARDLINK')
    return sorted(rows, key=lambda row: row['path'])


def file_digest(path):
    digest = hashlib.sha256()
    size = 0
    descriptor = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    with os.fdopen(descriptor, 'rb') as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode):
            raise Deferred('ARCHIVE_NOT_REGULAR')
        for block in iter(lambda: stream.read(65536), b''):
            size += len(block)
            if size > 1024**3:
                raise Deferred('ARCHIVE_LIMIT')
            digest.update(block)
        after = os.fstat(stream.fileno())
    current = Path(path).lstat()
    key = lambda value: (value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns)
    if key(before) != key(after) or key(after) != key(current):
        raise Deferred('SOURCE_CHANGED_DURING_READ')
    return {'bytes': size, 'sha256': digest.hexdigest()}


def archives(source, staging, rows, uid, gid):
    result = {}
    for name in ('work', 'home'):
        target = staging / (name + '.tar')
        if target.exists():
            raise Deferred('PARTIAL_PREPARATION_REQUIRES_RECONCILIATION')
        with tarfile.open(target, 'x', format=tarfile.PAX_FORMAT, dereference=False) as archive:
            os.chmod(target, 0o600)
            for row in rows:
                path = Path(row['path'])
                if path.parts[0] == name and row['kind'] != 'root':
                    archive.add(source / path, arcname=Path(*path.parts[1:]).as_posix(), recursive=False)
        os.chown(target, uid, gid)
        with target.open('rb') as stream:
            os.fsync(stream.fileno())
        result[name] = file_digest(target)
    sync(staging)
    if inventory(source) != rows:
        raise Deferred('SOURCE_CHANGED_DURING_PREPARATION')
    return result
