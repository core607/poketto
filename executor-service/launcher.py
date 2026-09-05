#!/usr/bin/env python3
"""Fixed low-privilege launcher. Only SRT may interpret an execution payload."""
import json
import os
from pathlib import Path
import sys


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
           'PATH': f'{tools}:{tools}/extracted/usr/bin:/usr/bin:/bin',
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
    elif record['mode'] == 'execute':
        command = ['/bin/bash', '--noprofile', '--norc', '-c',
                   'cd -- "$1" || exit; exec /bin/bash --noprofile --norc -c "$2"',
                   'execute', str(root / 'work/repository'), record['command']]
    else:
        raise SystemExit('Invalid execution mode')
    os.close(bootstrap_fd)
    os.execve(str(tools / 'node'), [str(tools / 'node'), str(srt), '--settings', record['settings'], '--', *command], env)


if __name__ == '__main__':
    main()
