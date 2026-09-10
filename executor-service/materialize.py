"""Bounded host-to-worktree transfers with frozen compare-and-replace installation."""
from contextlib import ExitStack
import hashlib
import os
from pathlib import Path
import re
import stat
import uuid

from session_files import CaptureRejected, MAX_CHUNK_BYTES, selected_paths

MAX_FILE_BYTES = 128 * 1024 * 1024


def _hash(value):
    if value is not None and (not isinstance(value, str) or re.fullmatch('[0-9a-f]{64}', value) is None):
        raise CaptureRejected('Invalid materialization hash')


def _directory(stack, parent, name):
    fd = os.open(name, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=parent)
    stack.callback(os.close, fd)
    return fd


def _current(parent, name):
    try:
        fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
    except FileNotFoundError:
        return None, 0o600
    with os.fdopen(fd, 'rb') as stream:
        info = os.fstat(stream.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or info.st_size > MAX_FILE_BYTES:
            raise CaptureRejected('Materialization target is unavailable or unsafe')
        digest, size = hashlib.sha256(), 0
        while block := stream.read(MAX_CHUNK_BYTES):
            size += len(block)
            if size > MAX_FILE_BYTES:
                raise CaptureRejected('Materialization target exceeds its bound')
            digest.update(block)
        return digest.hexdigest(), 0o600 | (info.st_mode & 0o111)


class IncomingFile:
    """The supervisor supplies the lease root and exec identity, never the command.

    Keep one transfer per lease. The caller holds the lease filesystem lock for
    every method and freezes the complete command cgroup throughout install().
    Staging is outside command-readable paths and is charged to the lease tmpfs.
    Remote authority and the host baseline change separately, after acknowledgement.
    """

    def __init__(self, root, path, size, digest, expected, delete=False):
        selected_paths([path], [])
        _hash(digest)
        _hash(expected)
        if (type(size) is not int or not 0 <= size <= MAX_FILE_BYTES or digest is None
                or type(delete) is not bool or (delete and size != 0)):
            raise CaptureRejected('Invalid materialization size or operation')
        self.id = str(uuid.uuid4())
        self.root = Path(root)
        self.path, self.size, self.digest, self.expected, self.delete = path, size, digest, expected, delete
        self.received = 0
        self.hasher = hashlib.sha256()
        self.result = None
        self.closed = False
        self.stage = self.root / ('incoming-' + self.id)
        self.fd = os.open(self.stage, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)

    def append(self, offset, content):
        if (self.closed or self.result is not None or type(offset) is not int or offset != self.received
                or not isinstance(content, bytes) or not 0 < len(content) <= MAX_CHUNK_BYTES
                or self.received + len(content) > self.size):
            raise CaptureRejected('Invalid materialization chunk')
        view = memoryview(content)
        try:
            while view:
                count = os.write(self.fd, view)
                if count <= 0:
                    raise OSError('Materialization staging write failed')
                view = view[count:]
        except OSError as error:
            self.close()
            raise CaptureRejected('Materialization staging write failed') from error
        self.hasher.update(content)
        self.received += len(content)

    def install(self, uid, gid):
        if self.result is not None:
            return self.result
        if self.closed or self.received != self.size or self.hasher.hexdigest() != self.digest:
            raise CaptureRejected('Materialization bytes are incomplete or corrupt')
        if os.fstat(self.fd).st_size != self.size:
            raise CaptureRejected('Materialization staging size changed')
        verified = hashlib.sha256()
        for offset in range(0, self.size, MAX_CHUNK_BYTES):
            verified.update(os.pread(self.fd, min(MAX_CHUNK_BYTES, self.size - offset), offset))
        if verified.hexdigest() != self.digest:
            raise CaptureRejected('Materialization staging bytes changed')
        try:
            with ExitStack() as handles:
                root = _directory(handles, None, self.root)
                work = _directory(handles, root, 'work')
                parent = _directory(handles, work, 'repository')
                parts = self.path.split('/')
                for name in parts[:-1]:
                    try:
                        child = _directory(handles, parent, name)
                    except FileNotFoundError:
                        if self.expected is not None:
                            raise CaptureRejected('Local file changed before materialization') from None
                        if self.delete:
                            return self._finished(False)
                        os.mkdir(name, 0o700, dir_fd=parent)
                        child = _directory(handles, parent, name)
                        os.fchown(child, uid, gid)
                    parent = child
                current, mode = _current(parent, parts[-1])
                if current != self.expected:
                    raise CaptureRejected('Local file changed before materialization')
                if not self.delete and current == self.digest:
                    return self._finished(False)
                if self.delete:
                    if current is not None:
                        os.unlink(parts[-1], dir_fd=parent)
                else:
                    os.fchown(self.fd, uid, gid)
                    os.fchmod(self.fd, mode)
                    os.replace(self.stage.name, parts[-1], src_dir_fd=root, dst_dir_fd=parent)
                return self._finished(current is not None if self.delete else current != self.digest)
        except OSError as error:
            raise CaptureRejected('Materialization target is unavailable or unsafe') from error

    def _finished(self, changed):
        self.result = {'path': self.path, 'sha256': None if self.delete else self.digest, 'changed': changed}
        self.close()
        return self.result

    def close(self):
        if not self.closed:
            self.closed = True
            os.close(self.fd)
            self.stage.unlink(missing_ok=True)
