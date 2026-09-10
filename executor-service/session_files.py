"""Bounded selected-file capture from a quiescent, worker-owned session root."""
from contextlib import ExitStack
import os
import stat
import unicodedata


MAX_SELECTED = 64
MAX_TEXT_BYTES = 4 * 1024 * 1024
MAX_PATH_BYTES = 4096


class CaptureRejected(Exception):
    pass


def selected_paths(writes, deletes):
    if not isinstance(writes, list) or not isinstance(deletes, list):
        raise CaptureRejected('Selections must be path lists')
    if not 1 <= len(writes) + len(deletes) <= MAX_SELECTED:
        raise CaptureRejected('Select between 1 and 64 files')
    seen = set()
    for path in writes + deletes:
        if not isinstance(path, str):
            raise CaptureRejected('Invalid selected path')
        try:
            encoded = path.encode('utf-8', errors='strict')
        except UnicodeError as error:
            raise CaptureRejected('Invalid selected path') from error
        if not 0 < len(encoded) <= MAX_PATH_BYTES or '\\' in path or any(ord(c) < 32 or ord(c) == 127 for c in path):
            raise CaptureRejected('Invalid selected path')
        parts = path.split('/')
        if any(part in ('', '.', '..') for part in parts):
            raise CaptureRejected('Invalid selected path')
        normalized = unicodedata.normalize('NFC', path)
        key = unicodedata.normalize('NFC', normalized.upper().lower())
        if '.git' in key.split('/') or key in seen:
            raise CaptureRejected('Reserved or colliding selected path')
        seen.add(key)
    return tuple(writes), tuple(deletes)


def _directory(stack, parent, name):
    fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
    stack.callback(os.close, fd)
    return fd


def capture_text(session_root, writes, deletes):
    """Call only while the entire session process tree is frozen or confirmed empty.

    session_root is the supervisor's lease mount, never an agent-supplied path.
    The caller must keep that mount alive for this call. Every selected write must
    exist as a regular UTF-8 file. Deletions come only from the explicit list;
    unselected files and missing materialized media cannot become deletions.
    Repository authorization and revision checks remain with the application.
    """
    writes, deletes = selected_paths(writes, deletes)
    captured = {}
    remaining = MAX_TEXT_BYTES
    try:
        with ExitStack() as roots:
            root = _directory(roots, None, session_root)
            work = _directory(roots, root, 'work')
            repository = _directory(roots, work, 'repository')
            for path in writes:
                with ExitStack() as handles:
                    parent = repository
                    parts = path.split('/')
                    for part in parts[:-1]:
                        parent = _directory(handles, parent, part)
                    fd = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
                    handles.callback(os.close, fd)
                    before = os.fstat(fd)
                    if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
                        raise CaptureRejected('Selected writes must be ordinary files')
                    if before.st_size > remaining:
                        raise CaptureRejected('Selected text exceeds 4 MiB')
                    content = bytearray()
                    while True:
                        block = os.read(fd, min(65536, remaining - len(content) + 1))
                        if not block:
                            break
                        content.extend(block)
                        if len(content) > remaining:
                            raise CaptureRejected('Selected text exceeds 4 MiB')
                    after = os.fstat(fd)
                    current = os.stat(parts[-1], dir_fd=parent, follow_symlinks=False)
                    identity = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                    if identity(before) != identity(after) or identity(after) != identity(current):
                        raise CaptureRejected('Selected file changed during capture')
                    try:
                        captured[path] = content.decode('utf-8', errors='strict')
                    except UnicodeError as error:
                        raise CaptureRejected('Selected writes must be UTF-8 text') from error
                    remaining -= len(content)
    except OSError as error:
        raise CaptureRejected('Selected file is unavailable or unsafe') from error
    return {'writes': captured, 'deletes': deletes}
