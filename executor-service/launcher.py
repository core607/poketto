#!/usr/bin/env python3
"""Fixed low-privilege bubblewrap launcher; no command runs before seccomp is installed."""
import hashlib
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys


# SRT 0.0.75's static x64 helper supplies the filter and its protected PID namespace.
# A different helper requires the live equivalence probe, not just a version change.
SECCOMP_SHA256 = '64fa7514fa7199584b836159885e59b9ce3684fb8fef7e13de49965b23f54f6b'
MIN_BWRAP_VERSION = (0, 6, 1)


def trusted_executable(path):
    """Reject writable ancestors as well as writable binaries before executing host tools."""
    path = Path(path)
    if not path.is_absolute() or '..' in path.parts:
        raise ValueError('Sandbox tool paths must be absolute')
    for current in (path, *path.parents):
        info = current.lstat()
        expected = stat.S_ISREG(info.st_mode) if current == path else stat.S_ISDIR(info.st_mode)
        if not expected or info.st_uid != 0 or info.st_mode & 0o022:
            raise ValueError('Sandbox tools and their parents must be root-owned and not writable by other accounts')
    if not path.stat().st_mode & 0o111:
        raise ValueError('Sandbox tool is not executable')


def verify_tools(tools):
    """Used at supervisor startup and again in each unprivileged launch."""
    tools = Path(tools)
    bwrap = tools / 'extracted/usr/bin/bwrap'
    seccomp = tools / 'node_modules/@anthropic-ai/sandbox-runtime/vendor/seccomp/x64/apply-seccomp'
    for path in (bwrap, seccomp):
        trusted_executable(path)
    if hashlib.sha256(seccomp.read_bytes()).hexdigest() != SECCOMP_SHA256:
        raise ValueError('Sandbox apply-seccomp checksum mismatch; prepare the pinned SRT 0.0.75 tools')
    version = subprocess.run([str(bwrap), '--version'], check=True, stdin=subprocess.DEVNULL,
                             capture_output=True, text=True, timeout=5,
                             env={'PATH': '/usr/bin:/bin'}).stdout.strip()
    match = re.fullmatch(r'bubblewrap (\d+)\.(\d+)\.(\d+)', version)
    if not match or tuple(map(int, match.groups())) < MIN_BWRAP_VERSION:
        raise ValueError('Sandbox requires bubblewrap 0.6.1 or newer')
    return bwrap, seccomp


def sandbox_arguments(bwrap, seccomp, policy, command):
    # Reject policy extensions until the translator implements them; silently ignoring a
    # new deny or a weaker-network option would change the worker's containment contract.
    if (set(policy) != {'filesystem', 'network', 'enableWeakerNestedSandbox'}
            or policy['enableWeakerNestedSandbox'] is not False
            or policy['network'] != {'allowedDomains': [], 'deniedDomains': [], 'allowAllUnixSockets': False}):
        raise ValueError('Unsupported sandbox policy')
    files = policy['filesystem']
    if (set(files) != {'denyRead', 'allowRead', 'denyWrite', 'allowWrite'}
            or files['denyRead'] != ['/', '/sys'] or files['denyWrite'] != []):
        raise ValueError('Unsupported sandbox filesystem policy')
    read, write = files['allowRead'], files['allowWrite']
    for paths in (read, write):
        if not isinstance(paths, list) or len(paths) != len(set(paths)):
            raise ValueError('Sandbox grants must be distinct path lists')
        for path in paths:
            if (not isinstance(path, str) or not path.startswith('/') or path == '/'
                    or '\0' in path or os.path.normpath(path) != path):
                raise ValueError('Sandbox grants must be normalized absolute paths')
    if any(Path(a).is_relative_to(b) or Path(b).is_relative_to(a) for a in read for b in write):
        raise ValueError('Sandbox read and write grants must be disjoint')
    args = [str(bwrap), '--new-session', '--die-with-parent',
            '--unshare-user', '--cap-drop', 'ALL', '--unshare-net']
    for path in read:
        if path in ('/dev', '/proc'):
            continue
        if path in ('/bin', '/lib', '/lib64') and Path(path).is_symlink():
            # Preserve usr-merged layout without exposing a resolved target outside a grant.
            resolved = Path(path).resolve(strict=True)
            if not any(resolved.is_relative_to(grant) for grant in read if grant != path):
                raise ValueError('Sandbox system link resolves outside readable paths')
            args += ['--symlink', os.readlink(path), path]
        else:
            args += ['--ro-bind', path, path]
    for path in write:
        args += ['--bind', path, path]
    # Empty-root scaffolding must not be writable; individual write mounts remain writable.
    args += ['--dev', '/dev', '--unshare-pid', '--proc', '/proc', '--remount-ro', '/',
             '--', str(seccomp), *command]
    return args


# Metadata updates run inside the sandbox as the execution account. Git refs are never
# authoritative write preconditions; the host supplies the acknowledged commit.
INSTALL_BASELINE = r'''
import pathlib, subprocess, sys
root, commit = pathlib.Path(sys.argv[1]), sys.argv[2]
repository = root / 'work/repository'
def git(*args, check=True, capture_output=False):
    return subprocess.run(['git', '-C', str(repository), '-c', 'core.hooksPath=/dev/null',
        '-c', 'core.fsmonitor=false', '-c', 'gc.auto=0', '-c', 'protocol.allow=never',
        '-c', 'protocol.file.allow=always', '-c', 'user.name=Poketto',
        '-c', 'user.email=noreply@poketto.invalid', *args], check=check, capture_output=capture_output, text=True)
result = git('fetch', '--no-auto-maintenance', '--no-tags', '--no-write-fetch-head',
             str(root / 'baseline.bundle'), commit, check=False)
if result.returncode:
    raise SystemExit(10)
staged = git('diff', '--cached', '--quiet', check=False)
if staged.returncode == 1:
    snapshot = git('stash', 'create', capture_output=True).stdout.strip()
    if snapshot:
        git('stash', 'store', '-m', 'Staged work before authoritative baseline update', snapshot)
elif staged.returncode:
    raise SystemExit(12)
git('reset', '--mixed', '--quiet', commit)
'''


def main():
    if os.geteuid() == 0 or len(sys.argv) != 2:
        raise SystemExit('Dedicated unprivileged account required')
    record = json.loads(Path(sys.argv[1]).read_bytes())
    root = Path(record['root'])
    tools = Path(record['tools'])
    bwrap, seccomp = verify_tools(tools)
    policy = json.loads(Path(record['settings']).read_bytes())
    # Startup never inspects a command-mutated working directory.
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    bootstrap_fd = os.open('bootstrap', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
    os.fchdir(bootstrap_fd)
    os.close(root_fd)
    # The compile cache is read-only here: install-compile-cache.sh, run as root, is its only writer.
    env = {'HOME': str(root / 'home'), 'TMPDIR': '/tmp',
           'PATH': f'{root}/bootstrap:{tools}:{tools}/extracted/usr/bin:/usr/bin:/bin',
           'POKETTO_BRIDGE': str(root / 'bridge'), 'NODE_COMPILE_CACHE': str(tools / 'compile-cache'),
           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null', 'SANDBOX_RUNTIME': '1'}
    if record['mode'] == 'initialize':
        # Parameters originate in verified server leases, and are passed as positional arguments.
        command = ['/bin/bash', '-c',
            'set -eu; git -c core.hooksPath=/dev/null clone --no-local --quiet "$1/snapshot.bundle" "$1/work/repository"; '
            'git -C "$1/work/repository" remote remove origin; '
            'git -C "$1/work/repository" -c core.hooksPath=/dev/null checkout --quiet --detach "$2"; '
            'test "$(git -C "$1/work/repository" rev-parse HEAD)" = "$2"; '
            'test ! -e "$1/work/repository/.git/objects/info/alternates"; '
            'test -z "$(git -C "$1/work/repository" remote)"',
            'initialize', str(root), record['commit']]
    elif record['mode'] == 'shell':
        command = ['/usr/bin/python3', '-I', str(root / 'bootstrap/shell_loop.py'), str(root / 'work/repository')]
    elif record['mode'] == 'baseline':
        command = ['/usr/bin/python3', '-c', INSTALL_BASELINE, str(root), record['commit']]
    elif record['mode'] == 'game':
        env.pop('POKETTO_BRIDGE')
        command = [str(tools / 'node'), '--max-old-space-size=64',
                   str(root / 'bootstrap/game-entry.mjs'), str(root / 'input.json')]
    else:
        raise SystemExit('Invalid execution mode')
    os.close(bootstrap_fd)
    os.execve(str(bwrap), sandbox_arguments(bwrap, seccomp, policy, command), env)


if __name__ == '__main__':
    main()
