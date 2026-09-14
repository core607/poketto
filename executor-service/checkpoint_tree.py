"""Bounded worktree framing. Callers must first contain every execution writer."""
from contextlib import ExitStack
import os
import stat
import struct


ENTRY = struct.Struct('>BIIQ')
MAX_PATH = 4096
MAX_DEPTH = 128


class CheckpointError(Exception):
    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def exact(stream, count):
    value = stream.read(count)
    if len(value) != count:
        raise CheckpointError('CORRUPT')
    return value


def directory(parent, name):
    return os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)


def safe_name(raw):
    try:
        name = raw.decode('utf-8')
    except UnicodeError as error:
        raise CheckpointError('UNSUPPORTED_PATH') from error
    parts = name.split('/')
    if (not 0 < len(raw) <= MAX_PATH or len(parts) > MAX_DEPTH or
            any(part in ('', '.', '..') or '\0' in part for part in parts)):
        raise CheckpointError('UNSUPPORTED_PATH')
    if name != 'snapshot.bundle' and parts[0] != 'work':
        raise CheckpointError('UNSUPPORTED_PATH')
    return name, parts


def write_tree(root, output, max_entries, cancelled=lambda: False):
    """Preserve links as opaque bytes; never follow a command-controlled link."""
    count = 0

    def visit(parent, name, relative):
        nonlocal count
        count += 1
        if cancelled():
            raise CheckpointError('CANCELLED')
        if count > max_entries:
            raise CheckpointError('ENTRY_LIMIT')
        raw = relative.encode('utf-8')
        safe_name(raw)
        before = os.stat(name, dir_fd=parent, follow_symlinks=False)
        if relative in ('work', 'work/repository') and not stat.S_ISDIR(before.st_mode):
            raise CheckpointError('UNSUPPORTED_FILE')
        mode = stat.S_IMODE(before.st_mode) & 0o777
        if stat.S_ISDIR(before.st_mode):
            with ExitStack() as handles:
                fd = directory(parent, name)
                handles.callback(os.close, fd)
                same_entry(before, os.fstat(fd))
                output.write(ENTRY.pack(1, len(raw), mode, 0) + raw)
                # scandir is streamed; an oversized directory is never materialized in memory.
                with os.scandir(fd) as children:
                    for child in children:
                        visit(fd, child.name, relative + '/' + child.name)
                same_entry(before, os.fstat(fd))
        elif stat.S_ISREG(before.st_mode):
            fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
            with os.fdopen(fd, 'rb') as source:
                same_entry(before, os.fstat(source.fileno()))
                if before.st_nlink != 1:
                    raise CheckpointError('UNSUPPORTED_FILE')
                output.write(ENTRY.pack(2, len(raw), mode, before.st_size) + raw)
                remaining = before.st_size
                while remaining:
                    if cancelled():
                        raise CheckpointError('CANCELLED')
                    part = source.read(min(65536, remaining))
                    if not part:
                        raise CheckpointError('TREE_CHANGED')
                    output.write(part)
                    remaining -= len(part)
                same_entry(before, os.fstat(source.fileno()))
        elif stat.S_ISLNK(before.st_mode) and relative != 'snapshot.bundle':
            target = os.fsencode(os.readlink(name, dir_fd=parent))
            if not 0 < len(target) <= MAX_PATH:
                raise CheckpointError('UNSUPPORTED_FILE')
            output.write(ENTRY.pack(3, len(raw), 0, len(target)) + raw + target)
            same_entry(before, os.stat(name, dir_fd=parent, follow_symlinks=False))
        else:
            raise CheckpointError('UNSUPPORTED_FILE')

    with ExitStack() as handles:
        fd = directory(None, root)
        handles.callback(os.close, fd)
        work = directory(fd, 'work')
        handles.callback(os.close, work)
        repository = directory(work, 'repository')
        handles.callback(os.close, repository)
        # The root-owned original export is retained separately from mutable work/.git.
        visit(fd, 'snapshot.bundle', 'snapshot.bundle')
        visit(fd, 'work', 'work')
    output.write(ENTRY.pack(0, 0, 0, 0))


def same_entry(before, after):
    fields = ('st_dev', 'st_ino', 'st_mode', 'st_size', 'st_mtime_ns', 'st_ctime_ns', 'st_nlink')
    if any(getattr(before, name) != getattr(after, name) for name in fields):
        raise CheckpointError('TREE_CHANGED')


def read_tree(stream, root, max_entries, max_bytes, uid, gid, cancelled=lambda: False):
    """Restore only into a new empty supervisor-owned staging directory."""
    seen = set()
    directories = []
    total = 0
    with ExitStack() as handles:
        root_fd = directory(None, root)
        handles.callback(os.close, root_fd)
        if os.listdir(root_fd):
            raise CheckpointError('TARGET_OCCUPIED')
        while True:
            kind, length, mode, size = ENTRY.unpack(exact(stream, ENTRY.size))
            if kind == 0:
                if length or mode or size or stream.read(1):
                    raise CheckpointError('CORRUPT')
                if 'snapshot.bundle' not in seen or 'work' not in seen or 'work/repository' not in seen:
                    raise CheckpointError('CORRUPT')
                break
            if cancelled():
                raise CheckpointError('CANCELLED')
            if len(seen) >= max_entries or length > MAX_PATH:
                raise CheckpointError('ENTRY_LIMIT')
            name, parts = safe_name(exact(stream, length))
            total += size
            if name in seen or mode > 0o777 or total > max_bytes:
                raise CheckpointError('CORRUPT')
            if name == 'snapshot.bundle' and kind != 2:
                raise CheckpointError('CORRUPT')
            if name in ('work', 'work/repository') and kind != 1:
                raise CheckpointError('CORRUPT')
            seen.add(name)
            with ExitStack() as parents:
                parent = root_fd
                for part in parts[:-1]:
                    parent = directory(parent, part)
                    parents.callback(os.close, parent)
                leaf = parts[-1]
                if kind == 1 and size == 0:
                    os.mkdir(leaf, mode=0o700, dir_fd=parent)
                    directories.append((parts, mode))
                elif kind == 2:
                    fd = os.open(leaf, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                                 0o600, dir_fd=parent)
                    with os.fdopen(fd, 'wb') as output:
                        remaining = size
                        while remaining:
                            if cancelled():
                                raise CheckpointError('CANCELLED')
                            part = exact(stream, min(65536, remaining))
                            output.write(part)
                            remaining -= len(part)
                        output.flush()
                        if name == 'snapshot.bundle':
                            os.fchmod(output.fileno(), 0o440)
                            os.fchown(output.fileno(), os.geteuid(), gid)
                        else:
                            os.fchown(output.fileno(), uid, gid)
                            os.fchmod(output.fileno(), mode)
                elif kind == 3 and mode == 0 and 0 < size <= MAX_PATH:
                    target = exact(stream, size)
                    if b'\0' in target:
                        raise CheckpointError('CORRUPT')
                    os.symlink(target, os.fsencode(leaf), dir_fd=parent)
                    os.chown(leaf, uid, gid, dir_fd=parent, follow_symlinks=False)
                else:
                    raise CheckpointError('CORRUPT')
        # Keep directories traversable until all children are installed; no writer runs here.
        for parts, mode in reversed(directories):
            with ExitStack() as parents:
                fd = root_fd
                for part in parts:
                    fd = directory(fd, part)
                    parents.callback(os.close, fd)
                os.fchown(fd, uid, gid)
                os.fchmod(fd, mode)
