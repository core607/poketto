"""Restore a verified Sandcrab workspace changeset inside its fresh, writer-free template session."""
from contextlib import contextmanager
import errno
import os
import shutil
import stat
import sys
import tarfile
import uuid


DIRECTORY = os.O_DIRECTORY | os.O_NOFOLLOW | os.O_RDONLY
REGULAR = (tarfile.REGTYPE, tarfile.AREGTYPE)


def parts(name):
    if name == '.':
        return ()
    path = tuple(name.split('/'))
    if (len(name.encode()) > 4096 or len(path) > 64 or '\\' in name
            or any(not segment or segment in ('.', '..') or len(segment.encode()) > 255
                   or any(ord(c) < 32 or 127 <= ord(c) <= 159 for c in segment) for segment in path)):
        raise ValueError('Invalid changeset path')
    return path


@contextmanager
def directory(root, path):
    descriptor = os.dup(root)
    try:
        for name in path:
            child = os.open(name, DIRECTORY, dir_fd=descriptor)
            os.close(descriptor)
            descriptor = child
        yield descriptor
    finally:
        os.close(descriptor)


def remove(parent, name):
    try:
        mode = os.stat(name, dir_fd=parent, follow_symlinks=False).st_mode
    except FileNotFoundError:
        return
    if stat.S_ISDIR(mode):
        with directory(parent, (name,)) as child:
            os.fchmod(child, 0o700)
            for entry in os.listdir(child):
                remove(child, entry)
        os.rmdir(name, dir_fd=parent)
    else:
        os.unlink(name, dir_fd=parent)


def whiteout(root, path):
    if path[-1] != '.wh..wh..opq' and path[-1][4:] in ('', '.', '..'):
        raise ValueError('Invalid whiteout target')
    try:
        with directory(root, path[:-1]) as parent:
            if path[-1] == '.wh..wh..opq':
                for name in os.listdir(parent):
                    remove(parent, name)
            else:
                remove(parent, path[-1][4:])
    except OSError as failure:
        # A whiteout below a missing base directory or a base symlink hides nothing.
        if failure.errno not in (errno.ENOENT, errno.ENOTDIR, errno.ELOOP):
            raise


def check_entries(archive, byte_limit, entry_limit):
    entries = {}
    whiteouts = {}
    expanded = 0
    for item in archive:
        path = parts(item.name)
        if path in entries or len(entries) >= entry_limit or item.size < 0 or item.sparse is not None:
            raise ValueError('Invalid or oversized changeset')
        if not path and not item.isdir():
            raise ValueError('Workspace root must be a directory')
        if any(part.startswith('.wh.') for part in path[:-1]):
            raise ValueError('Whiteout used as a directory')
        if path and path[-1].startswith('.wh.'):
            if item.type not in REGULAR or item.size != 0:
                raise ValueError('Invalid whiteout')
            whiteouts[path] = None
        else:
            if item.type not in (*REGULAR, tarfile.DIRTYPE, tarfile.SYMTYPE, tarfile.LNKTYPE):
                raise ValueError('Unsupported changeset node')
            if item.type not in REGULAR and item.size != 0:
                raise ValueError('Unexpected entry payload')
            if item.uid != os.geteuid() or item.gid != os.getegid():
                raise ValueError('Unexpected changeset owner')
            if item.islnk():
                parts(item.linkname)
            expanded += item.size
            if expanded > byte_limit:
                raise ValueError('Expanded changeset exceeds its copy limit')
        entries[path] = item
    for path, item in entries.items():
        if any(parent in entries and not entries[parent].isdir() for parent in (path[:n] for n in range(1, len(path)))):
            raise ValueError('Changeset parent is not a directory')
        if item.islnk():
            source = parts(item.linkname)
            if source not in entries or entries[source].type not in REGULAR or source in whiteouts:
                raise ValueError('Hard link requires a regular file from this changeset')
    return entries, whiteouts


def apply(root, stream, byte_limit, entry_limit):
    with tarfile.open(fileobj=stream, mode='r:') as archive:
        entries, markers = check_entries(archive, byte_limit, entry_limit)
        for path in markers:
            whiteout(root, path)
        directories = sorted((path for path, item in entries.items() if item.isdir()), key=len)
        for path in directories:
            if not path:
                continue
            with directory(root, path[:-1]) as parent:
                try:
                    child = os.open(path[-1], DIRECTORY, dir_fd=parent)
                except (FileNotFoundError, NotADirectoryError):
                    remove(parent, path[-1])
                    os.mkdir(path[-1], 0o700, dir_fd=parent)
                else:
                    os.fchmod(child, 0o700)
                    os.close(child)
        for path, item in entries.items():
            if item.isdir() or item.islnk() or path in markers:
                continue
            with directory(root, path[:-1]) as parent:
                name = path[-1]
                remove(parent, name)
                if item.issym():
                    os.symlink(item.linkname, name, dir_fd=parent)
                else:
                    fd = os.open(name, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600, dir_fd=parent)
                    with os.fdopen(fd, 'wb') as output, archive.extractfile(item) as content:
                        shutil.copyfileobj(content, output)
                        output.flush()
                        os.fchmod(output.fileno(), item.mode & 0o7777)
                os.utime(name, (item.mtime, item.mtime), dir_fd=parent, follow_symlinks=False)
        for path, item in entries.items():
            if item.islnk():
                source = parts(item.linkname)
                with directory(root, source[:-1]) as src, directory(root, path[:-1]) as dst:
                    remove(dst, path[-1])
                    os.link(source[-1], path[-1], src_dir_fd=src, dst_dir_fd=dst, follow_symlinks=False)
        for path in reversed(directories):
            item = entries[path]
            with directory(root, path) as parent:
                os.fchmod(parent, item.mode & 0o7777)
                os.utime(parent, (item.mtime, item.mtime))


def main():
    name, maximum, entries = sys.argv[1:]
    if name != '.poketto-upper-' + str(uuid.UUID(name.removeprefix('.poketto-upper-'))):
        raise ValueError('Invalid staged archive')
    root = os.open('/workspace', DIRECTORY)
    try:
        with os.fdopen(os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=root), 'rb') as stream:
            os.unlink(name, dir_fd=root)
            apply(root, stream, int(maximum), int(entries))
    finally:
        os.close(root)


if __name__ == '__main__':
    main()
