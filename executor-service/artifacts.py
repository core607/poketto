"""Ephemeral immutable artifacts, retained only inside one lease's bounded tmpfs."""
import base64
from dataclasses import dataclass
import hashlib
import math
import os
from pathlib import Path
import re
import stat
import threading
import time
import uuid

from binary_capture import BinaryCapture, MAX_BINARY_BYTES
from session_files import CaptureRejected, MAX_CHUNK_BYTES

MAX_ARTIFACTS = 16
MAX_TOTAL_BYTES = 256 * 1024 * 1024
LIFETIME_SECONDS = 300


class ArtifactRejected(Exception):
    def __init__(self, code):
        self.code = code


def _version(info):
    return info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns


@dataclass
class _Entry:
    path: Path
    fd: int
    name: str
    media_type: str
    size: int
    digest: str
    deadline: float
    version: tuple
    truncated: bool


class ArtifactStore:
    """The caller retains the lease mount and freezes the cgroup for capture().

    Neither filenames nor identifiers select host paths: lookup uses this store's
    private map. No global deduplication, persistent registry or reusable grants.
    Reads and expiry share a lock; close completes before the mount is released.
    """

    def __init__(self, root, clock=time.monotonic, maximum=MAX_TOTAL_BYTES, count=MAX_ARTIFACTS):
        if type(maximum) is not int or not 0 < maximum <= MAX_TOTAL_BYTES or type(count) is not int or not 0 < count <= MAX_ARTIFACTS:
            raise ArtifactRejected('ARTIFACT_CAPACITY')
        self.root, self.clock, self.maximum, self.count = Path(root), clock, maximum, count
        self.directory = self.root / 'artifacts'
        self.directory.mkdir(mode=0o700)
        self.entries, self.closed, self.lock = {}, False, threading.RLock()

    def _admit(self, media_type, size=0):
        if self.closed:
            raise ArtifactRejected('ARTIFACT_UNAVAILABLE')
        if not isinstance(media_type, str) or len(media_type) > 128 or re.fullmatch('[a-z0-9.+-]+/[a-z0-9.+-]+', media_type) is None:
            raise ArtifactRejected('INVALID_ARTIFACT')
        self.reap()
        remaining = self.maximum - sum(entry.size for entry in self.entries.values())
        if len(self.entries) >= self.count or size > remaining or size > MAX_BINARY_BYTES:
            raise ArtifactRejected('ARTIFACT_CAPACITY')
        return min(remaining, MAX_BINARY_BYTES)

    def _keep(self, identifier, fd, name, media_type, size, digest, truncated):
        path = self.directory / identifier
        try:
            entry = _Entry(path, fd, name, media_type, size, digest,
                           self.clock() + LIFETIME_SECONDS, _version(os.fstat(fd)), truncated)
            self.entries[identifier] = entry
            return self._metadata(identifier, entry)
        except BaseException:
            os.close(fd)
            path.unlink(missing_ok=True)
            raise

    def capture(self, path, media_type, cancelled=lambda: False):
        with self.lock:
            available = self._admit(media_type)
            captured = None
            try:
                captured = BinaryCapture(self.root, path, cancelled, maximum=available)
                if captured.id in self.entries or os.path.lexists(self.directory / captured.id):
                    raise ArtifactRejected('ARTIFACT_UNAVAILABLE')
                fd = captured.transfer(self.directory / captured.id)
                return self._keep(captured.id, fd, path.split('/')[-1], media_type,
                                  captured.size, captured.digest, False)
            except (OSError, CaptureRejected):
                raise ArtifactRejected('ARTIFACT_UNAVAILABLE') from None
            finally:
                if captured is not None:
                    captured.close()

    def put(self, name, content, media_type='text/plain', truncated=False):
        with self.lock:
            if not isinstance(content, (bytes, bytearray)) or type(truncated) is not bool:
                raise ArtifactRejected('INVALID_ARTIFACT')
            if name not in ('stdout.txt', 'stderr.txt'):
                raise ArtifactRejected('INVALID_ARTIFACT')
            self._admit(media_type, len(content))
            identifier, fd, created = str(uuid.uuid4()), None, False
            path = self.directory / identifier
            try:
                fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
                created = True
                remaining = memoryview(content)
                while remaining:
                    written = os.write(fd, remaining)
                    if written <= 0:
                        raise OSError('Artifact write failed')
                    remaining = remaining[written:]
                kept, fd = fd, None
                return self._keep(identifier, kept, name, media_type, len(content),
                                  hashlib.sha256(content).hexdigest(), truncated)
            except OSError:
                if fd is not None:
                    os.close(fd)
                if created:
                    path.unlink(missing_ok=True)
                raise ArtifactRejected('ARTIFACT_UNAVAILABLE') from None

    def _metadata(self, identifier, entry):
        return {'artifactId': identifier, 'name': entry.name, 'mediaType': entry.media_type,
                'bytes': entry.size, 'sha256': entry.digest, 'truncated': entry.truncated,
                'expiresInSeconds': max(0, math.ceil(entry.deadline - self.clock()))}

    def read(self, identifier, offset, limit):
        with self.lock:
            self.reap()
            entry = self.entries.get(identifier)
            if self.closed or entry is None:
                raise ArtifactRejected('ARTIFACT_UNAVAILABLE')
            if type(offset) is not int or not 0 <= offset <= entry.size or type(limit) is not int or not 1 <= limit <= MAX_CHUNK_BYTES:
                raise ArtifactRejected('INVALID_ARTIFACT_RANGE')
            try:
                info = os.fstat(entry.fd)
                if not stat.S_ISREG(info.st_mode) or info.st_nlink != 1 or _version(info) != entry.version:
                    raise ArtifactRejected('ARTIFACT_UNAVAILABLE')
                content = os.pread(entry.fd, min(limit, entry.size - offset), offset)
                if len(content) != min(limit, entry.size - offset):
                    raise ArtifactRejected('ARTIFACT_UNAVAILABLE')
                if entry.deadline <= self.clock():
                    self.remove(identifier)
                    raise ArtifactRejected('ARTIFACT_UNAVAILABLE')
                return {**self._metadata(identifier, entry), 'offset': offset,
                        'data': base64.b64encode(content).decode('ascii')}
            except OSError:
                raise ArtifactRejected('ARTIFACT_UNAVAILABLE') from None

    def remove(self, identifier):
        with self.lock:
            entry = self.entries.pop(identifier, None)
            if entry is not None:
                try:
                    os.close(entry.fd)
                finally:
                    entry.path.unlink(missing_ok=True)

    def reap(self):
        with self.lock:
            for identifier, entry in list(self.entries.items()):
                if entry.deadline <= self.clock():
                    self.remove(identifier)

    def close(self):
        with self.lock:
            self.closed = True
            failure = None
            for identifier in list(self.entries):
                try:
                    self.remove(identifier)
                except OSError as error:
                    failure = error
            if failure is not None:
                raise failure
