#!/usr/bin/env python3
"""Fixed low-privilege launcher. Only SRT may interpret an execution payload."""
import json
import os
from pathlib import Path
import sys


# Metadata updates run inside SRT as the execution account. Git refs are never
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
    srt = tools / 'node_modules/@anthropic-ai/sandbox-runtime/dist/cli.js'
    if json.loads((srt.parent.parent / 'package.json').read_bytes())['version'] != '0.0.75':
        raise SystemExit('SRT version mismatch')
    # Third-party SRT startup must not inspect a command-mutated working directory.
    root_fd = os.open(root, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
    bootstrap_fd = os.open('bootstrap', os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=root_fd)
    os.fchdir(bootstrap_fd)
    os.close(root_fd)
    env = {'HOME': str(root / 'home'), 'TMPDIR': '/tmp',
           'PATH': f'{root}/bootstrap:{tools}:{tools}/extracted/usr/bin:/usr/bin:/bin',
           'POKETTO_BRIDGE': str(root / 'bridge'),
           'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null'}
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
    else:
        raise SystemExit('Invalid execution mode')
    os.close(bootstrap_fd)
    os.execve(str(tools / 'node'), [str(tools / 'node'), str(srt), '--settings', record['settings'], '--', *command], env)


if __name__ == '__main__':
    main()
