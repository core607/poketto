"""Immutable, bounded binary snapshots held outside the command-readable worktree."""
from contextlib import ExitStack
import base64
import hashlib
import os
from pathlib import Path
import stat
import uuid

from session_files import CaptureRejected, MAX_CHUNK_BYTES, _directory, selected_paths

MAX_BINARY_BYTES = 128 * 1024 * 1024


class BinaryCapture:
    """Construct only with the lease mount retained and the whole command cgroup frozen.

    The supervisor supplies root. Keep at most one capture per lease and close it
    on release, command completion, cancellation, and cleanup. Snapshot bytes are
    charged to the lease tmpfs, not accumulated in worker process memory.
    """

    def __init__(self, root, path, cancelled=lambda: False, maximum=MAX_BINARY_BYTES):
        selected_paths([path], [])
        if type(maximum) is not int or not 0 <= maximum <= MAX_BINARY_BYTES:
            raise CaptureRejected('Invalid binary capture bound', 'INVALID_ARGUMENTS')
        self.id, self.path = str(uuid.uuid4()), path
        self.stage = Path(root) / ('outgoing-' + self.id)
        self.fd = None
        self.created = False
        self.size = 0
        repository_open = False
        source_open = False
        try:
            with ExitStack() as handles:
                lease = _directory(handles, None, root)
                work = _directory(handles, lease, 'work')
                parent = _directory(handles, work, 'repository')
                repository_open = True
                parts = path.split('/')
                for part in parts[:-1]:
                    parent = _directory(handles, parent, part)
                source = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=parent)
                handles.callback(os.close, source)
                source_open = True
                before = os.fstat(source)
                if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
                    raise CaptureRejected('Binary source must be one regular file', 'NOT_REGULAR_FILE')
                if before.st_size > maximum:
                    raise CaptureRejected('Binary source exceeds its bound', 'BINARY_LIMIT')
                self.fd = os.open(self.stage, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                self.created = True
                digest = hashlib.sha256()
                while block := os.read(source, MAX_CHUNK_BYTES):
                    if cancelled():
                        raise CaptureRejected('Binary capture was cancelled')
                    self.size += len(block)
                    if self.size > maximum:
                        raise CaptureRejected('Binary source exceeds its bound', 'BINARY_LIMIT')
                    digest.update(block)
                    remaining = memoryview(block)
                    while remaining:
                        written = os.write(self.fd, remaining)
                        if written <= 0:
                            raise CaptureRejected('Binary snapshot write failed')
                        remaining = remaining[written:]
                identity = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
                if (identity(before) != identity(os.fstat(source))
                        or identity(before) != identity(os.stat(parts[-1], dir_fd=parent, follow_symlinks=False))
                        or self.size != before.st_size):
                    raise CaptureRejected('Binary source changed during capture', 'FILE_CHANGED')
                self.digest = digest.hexdigest()
        except (OSError, CaptureRejected) as error:
            self.close()
            if isinstance(error, CaptureRejected):
                raise
            reason = 'NOT_FOUND' if repository_open and not source_open and isinstance(error, FileNotFoundError) else 'CAPTURE_UNAVAILABLE'
            raise CaptureRejected('Binary source is unavailable or unsafe', reason) from error

    def manifest(self):
        return {'captureId': self.id, 'writes': [{'path': self.path, 'bytes': self.size, 'sha256': self.digest}],
                'deletes': [], 'absent': []}

    def transfer(self, destination):
        """Transfer ownership to protected storage supplied by the supervisor."""
        if self.fd is None:
            raise CaptureRejected('Binary snapshot is unavailable')
        os.rename(self.stage, destination)
        fd, self.fd, self.created = self.fd, None, False
        return fd

    def chunk(self, index, offset, limit):
        if (self.fd is None or type(index) is not int or index != 0
                or type(offset) is not int or not 0 <= offset <= self.size
                or type(limit) is not int or not 1 <= limit <= MAX_CHUNK_BYTES):
            raise CaptureRejected('Invalid binary snapshot chunk')
        content = os.pread(self.fd, min(limit, self.size - offset), offset)
        if len(content) != min(limit, self.size - offset):
            raise CaptureRejected('Binary snapshot is incomplete')
        return {'captureId': self.id, 'index': 0, 'offset': offset, 'data': base64.b64encode(content).decode('ascii')}

    def close(self):
        if self.fd is not None:
            os.close(self.fd)
            self.fd = None
        if self.created:
            self.stage.unlink(missing_ok=True)
            self.created = False
