"""Bounded host-to-worktree transfers with frozen compare-and-replace installation."""
from contextlib import ExitStack
from bisect import bisect_left
import hashlib
import os
from pathlib import Path
import re
import stat
import shutil
import tempfile
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

    def __init__(self, root, path, size, digest, expected, delete=False, allow_identical=False):
        selected_paths([path], [])
        _hash(digest)
        _hash(expected)
        if (type(size) is not int or not 0 <= size <= MAX_FILE_BYTES or digest is None
                or type(delete) is not bool or type(allow_identical) is not bool
                or (delete and (size != 0 or allow_identical))):
            raise CaptureRejected('Invalid materialization size or operation')
        self.id = str(uuid.uuid4())
        self.root = Path(root)
        self.path, self.size, self.digest, self.expected, self.delete = path, size, digest, expected, delete
        self.allow_identical = allow_identical
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
                if self.allow_identical and current == self.digest:
                    return self._finished(False)
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


class LocalMove:
    """Applies a host-prepared move while the caller holds the lease lock and frozen cgroup.

    Temporary hardlinks exist only during the frozen installation and rollback. They
    stay below the supervisor-owned lease root, on the same quota-charged filesystem.
    This primitive does not advance Git authority or update the host's baselines.
    """

    MAX_PATHS = 16384
    MAX_REPLACEMENTS = 32 * 1024 * 1024

    def __init__(self, root, source, destination, originals, relocations, replacements):
        selected_paths([source], [])
        selected_paths([destination], [])
        if (source.lower() in ('public', 'private') or destination.lower() in ('public', 'private')
                or source.split('/')[0].lower() == '.poketto' or destination.split('/')[0].lower() == '.poketto'
                or source == destination
                or source.startswith(destination + '/') or destination.startswith(source + '/')):
            raise CaptureRejected('Invalid move roots')
        if not all(isinstance(value, dict) for value in (originals, relocations, replacements)):
            raise CaptureRejected('Invalid move plan')
        self.paths = set(originals) | set(relocations) | set(relocations.values()) | set(replacements)
        if not relocations or len(self.paths) > self.MAX_PATHS:
            raise CaptureRejected('Move exceeds path capacity')
        for path in self.paths:
            selected_paths([path], [])
        for path, value in originals.items():
            if (not isinstance(value, dict) or set(value) != {'sha256', 'bytes', 'optional'}
                    or type(value['bytes']) is not int or not 0 <= value['bytes'] <= MAX_FILE_BYTES
                    or type(value['optional']) is not bool or value['sha256'] is None):
                raise CaptureRejected('Invalid move original')
            _hash(value['sha256'])
        for before, after in relocations.items():
            if (before not in originals or not (before == source or before.startswith(source + '/'))
                    or after != destination + before[len(source):] or after in originals):
                raise CaptureRejected('Invalid move relocation')
        if (len(set(relocations.values())) != len(relocations)
                or not set(originals) <= set(relocations) | set(replacements)
                or not set(replacements) <= set(originals) | set(relocations.values())
                or any(not isinstance(value, bytes) for value in replacements.values())
                or sum(len(value) for value in replacements.values()) > self.MAX_REPLACEMENTS):
            raise CaptureRejected('Invalid or oversized move replacements')
        reverse = {target: source for source, target in relocations.items()}
        if any(originals[reverse.get(path, path)]['optional'] for path in replacements):
            raise CaptureRejected('Move replacements require existing Git text')
        self.root, self.source, self.destination = Path(root), source, destination
        self.originals = {path: dict(value) for path, value in originals.items()}
        self.relocations, self.replacements = dict(relocations), dict(replacements)
        self.sorted_sources = sorted(self.relocations)
        self.result = None

    def _repository(self):
        with ExitStack() as handles:
            root = _directory(handles, None, self.root)
            work = _directory(handles, root, 'work')
            _directory(handles, work, 'repository')
        return self.root / 'work/repository'

    def _inspect(self, path):
        with ExitStack() as handles:
            root = _directory(handles, None, self.root)
            work = _directory(handles, root, 'work')
            parent = _directory(handles, work, 'repository')
            parts = path.split('/')
            try:
                for name in parts[:-1]:
                    parent = _directory(handles, parent, name)
            except FileNotFoundError:
                return None, 0o600
            return _current(parent, parts[-1])

    def _exists(self, path):
        with ExitStack() as handles:
            root = _directory(handles, None, self.root)
            work = _directory(handles, root, 'work')
            parent = _directory(handles, work, 'repository')
            parts = path.split('/')
            try:
                for name in parts[:-1]:
                    parent = _directory(handles, parent, name)
                os.stat(parts[-1], dir_fd=parent, follow_symlinks=False)
                return True
            except FileNotFoundError:
                return False

    def _source_entries(self, repository):
        source = repository / self.source
        if not self._exists(self.source):
            return set()
        pending, files, count = [source], set(), 0
        while pending:
            path = pending.pop()
            count += 1
            if count > self.MAX_PATHS * 2:
                raise CaptureRejected('Move source scan exceeds its bound')
            info = path.lstat()
            relative = path.relative_to(repository).as_posix()
            if stat.S_ISDIR(info.st_mode):
                prefix = relative + '/'
                index = bisect_left(self.sorted_sources, prefix)
                if index == len(self.sorted_sources) or not self.sorted_sources[index].startswith(prefix):
                    raise CaptureRejected('Move source contains an untracked directory')
                pending.extend(path.iterdir())
            elif stat.S_ISREG(info.st_mode) and info.st_nlink == 1:
                files.add(relative)
            else:
                raise CaptureRejected('Move source contains an unsafe entry')
        return files

    def check(self):
        repository = self._repository()
        if self._exists(self.destination):
            raise CaptureRejected('Local move destination already exists')
        actual = {path: self._inspect(path) for path in sorted(self.paths)}
        for path, (current, mode) in actual.items():
            expected = self.originals.get(path)
            if expected is None:
                if current is not None:
                    raise CaptureRejected('Local move destination changed')
            elif current != expected['sha256'] and not (expected['optional'] and current is None):
                raise CaptureRejected('Local move source or backlink changed')
        if not self._source_entries(repository) <= set(self.relocations):
            raise CaptureRejected('Move source contains untracked files')
        return actual

    def _already_applied(self):
        after = {path: None for path in self.paths}
        optional = set()
        for source, target in self.relocations.items():
            after[target] = self.originals[source]['sha256']
            if self.originals[source]['optional']:
                optional.add(target)
        for path, content in self.replacements.items():
            after[path] = hashlib.sha256(content).hexdigest()
            optional.discard(path)
        for path, expected in after.items():
            current, _ = self._inspect(path)
            if current != expected and not (current is None and path in optional):
                return False
        return True

    def install(self, uid, gid):
        if self.result is not None:
            return self.result
        repository = self._repository()
        if self._already_applied():
            self.result = {'changedPaths': len(self.paths), 'alreadyApplied': True}
            return self.result
        actual = self.check()
        stage = Path(tempfile.mkdtemp(prefix='move-install-', dir=self.root))
        backups, incoming, created = {}, {}, []
        moved = False
        discard = True
        reverse = {target: source for source, target in self.relocations.items()}
        try:
            for index, path in enumerate(self.replacements):
                original_path = reverse.get(path, path)
                if actual[original_path][0] is not None:
                    backup = stage / ('before-' + str(index))
                    os.link(repository / original_path, backup, follow_symlinks=False)
                    backups[original_path] = backup
            for index, (path, content) in enumerate(self.replacements.items()):
                temporary = stage / ('text-' + str(index))
                with temporary.open('xb') as out:
                    out.write(content)
                mode = actual.get(reverse.get(path, path), (None, 0o600))[1]
                temporary.chmod(mode)
                os.chown(temporary, uid, gid)
                incoming[path] = temporary

            def ensure_parent(path):
                parent = repository
                for name in path.split('/')[:-1]:
                    parent = parent / name
                    try:
                        info = parent.lstat()
                    except FileNotFoundError:
                        parent.mkdir(mode=0o700)
                        os.chown(parent, uid, gid)
                        created.append(parent)
                    else:
                        if not stat.S_ISDIR(info.st_mode):
                            raise CaptureRejected('Unsafe move destination ancestor')

            source = repository / self.source
            destination = repository / self.destination
            if self._exists(self.source):
                ensure_parent(self.destination)
                os.replace(source, destination)
                moved = True
            for path, temporary in incoming.items():
                ensure_parent(path)
                os.replace(temporary, repository / path)
            self.result = {'changedPaths': len(self.paths), 'alreadyApplied': False}
            return self.result
        except BaseException:
            try:
                if moved:
                    os.replace(repository / self.destination, repository / self.source)
                for path, backup in backups.items():
                    os.replace(backup, repository / path)
                for parent in reversed(created):
                    if parent.exists():
                        parent.rmdir()
            except BaseException as rollback_error:
                discard = False
                raise RuntimeError('Local move rollback failed; close the lease') from rollback_error
            raise
        finally:
            if discard:
                shutil.rmtree(stage)
