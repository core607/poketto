"""Install a verified host revision into a live, unprivileged Git worktree."""
import fcntl
import os
from pathlib import Path
import shutil
import stat
import subprocess
import sys
import tempfile

workspace = Path('/workspace')
repository = workspace / 'work/repository'
bundle = workspace / '.poketto-git-baseline.bundle'
previous, target = sys.argv[1:]
env = {'PATH': '/usr/bin:/bin', 'HOME': str(workspace / 'home'), 'LC_ALL': 'C',
       'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_ATTR_NOSYSTEM': '1',
       'GIT_NO_REPLACE_OBJECTS': '1'}


def git(*args, index=None, check=True):
    settings = env if index is None else dict(env, GIT_INDEX_FILE=str(index))
    return subprocess.run(['/usr/bin/git', '-C', repository,
            '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false', '-c', 'gc.auto=0',
            '-c', 'protocol.allow=never', '-c', 'protocol.file.allow=always',
            '-c', 'user.name=Poketto', '-c', 'user.email=noreply@poketto.invalid', *args],
            env=settings, check=check, stdout=subprocess.PIPE)


def names_descriptor(path, handle):
    try:
        return os.path.samestat(path.lstat(), os.fstat(handle.fileno()))
    except FileNotFoundError:
        return False


def no_follow(path, flags):
    fd = os.open(path, flags | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
    if not stat.S_ISREG(os.fstat(fd).st_mode):
        os.close(fd)
        raise ValueError('Git index must be a regular file')
    return fd


def install():
    directories = git('rev-parse', '--path-format=absolute', '--git-dir', '--git-common-dir').stdout.splitlines()
    gitdir, common = [Path(os.fsdecode(name)).resolve(strict=True) for name in directories]
    if not (gitdir.is_relative_to(workspace) and common.is_relative_to(workspace)):
        raise ValueError('Git directory is outside the copy')
    if previous != '-' and git('cat-file', '-e', previous + '^{commit}', check=False).returncode:
        return 10
    git('fetch', '--no-auto-maintenance', '--no-tags', '--no-write-fetch-head', str(bundle), target)
    index = gitdir / 'index'
    lock = gitdir / 'index.lock'
    owner = gitdir / 'poketto-baseline-index'
    # Only the extra hard link left by an earlier helper identifies an owned index.lock.
    try:
        abandoned = open(owner, 'rb', opener=no_follow)
    except FileNotFoundError:
        pass
    else:
        with abandoned:
            fcntl.flock(abandoned, fcntl.LOCK_EX | fcntl.LOCK_NB)
            if names_descriptor(lock, abandoned):
                lock.unlink()
            if names_descriptor(owner, abandoned):
                owner.unlink()
    with open(owner, 'x+b', opener=no_follow) as guard:
        fcntl.flock(guard, fcntl.LOCK_EX | fcntl.LOCK_NB)
        try:
            os.link(owner, lock, follow_symlinks=False)
            if not names_descriptor(lock, guard):
                raise ValueError('Git index lock has a different owner')
            return locked_install(index, lock, guard)
        finally:
            for name in (lock, owner):
                if names_descriptor(name, guard):
                    name.unlink()


def locked_install(index, lock, guard):
    old = git('rev-parse', '--verify', 'HEAD').stdout.decode('ascii').strip()
    with tempfile.TemporaryDirectory(dir='/tmp') as stage:
        copy = Path(stage) / 'index'
        try:
            current = open(index, 'rb', opener=no_follow)
        except FileNotFoundError:
            pass
        else:
            with current, copy.open('xb') as backup:
                shutil.copyfileobj(current, backup)
        staged = git('diff', '--cached', '--quiet', index=copy, check=False).returncode
        if staged == 1:
            stash = git('stash', 'create', index=copy).stdout.decode('ascii').strip()
            if not stash:
                raise ValueError('Cannot retain staged Git changes')
            git('stash', 'store', '-m', 'Staged work before authoritative baseline update', stash, index=copy)
        elif staged:
            raise ValueError('Cannot inspect staged Git changes')
        fresh = Path(stage) / 'new-index'
        git('read-tree', target, index=fresh)
        guard.truncate(0)
        guard.seek(0)
        with fresh.open('rb') as source:
            shutil.copyfileobj(source, guard)
        guard.flush()
        os.fsync(guard.fileno())
    git('update-ref', '--create-reflog', '-m', 'Poketto authoritative baseline', 'HEAD', target, old)
    if not names_descriptor(lock, guard):
        raise ValueError('Git index lock changed during baseline installation')
    os.replace(lock, index)
    directory = os.open(index.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(directory)
    finally:
        os.close(directory)
    return 0


sys.exit(install())
