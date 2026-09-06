#!/usr/bin/env python3
"""Root-only, disposable systemd/cgroup test of pool membership and retained tmpfs charges."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid

from native_pool import NativePool
from resource_pool import properties


def run(args, check=True):
    return subprocess.run(args, check=check, capture_output=True, text=True, timeout=40)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True, help='New evidence file in an existing directory')
    args = parser.parse_args()
    if os.geteuid() != 0:
        parser.error('This isolated probe requires root')
    output = args.output.resolve()
    with output.open('x'):
        pass
    output.chmod(0o600)
    token = uuid.uuid4().hex[:8]
    root = Path('/run') / ('poketto-pool-proof-' + token)
    prefix = 'poketto-pool-proof-' + token + '-'
    pool = NativePool(token, '96M')
    dropin = Path('/run/systemd/system.control') / (pool.name + '.d')
    state = {'startedEpoch': time.time(), 'root': str(root), 'slice': pool.name,
             'sliceFile': str(pool.path), 'units': [], 'result': 'RUNNING', 'sourceSha256': {}}
    for name in ('resource_pool.py', 'resource_pool_probe.py', 'native_pool.py'):
        state['sourceSha256'][name] = hashlib.sha256(Path(__file__).with_name(name).read_bytes()).hexdigest()

    def save():
        output.write_text(json.dumps(state, indent=2) + '\n')

    def claim_unit(unit):
        found = run(['systemctl', 'show', unit + '.service', '--property=LoadState', '--value'], check=False)
        assert found.stdout.strip() == 'not-found', 'Probe unit must be new'
        state['units'].append(unit)
        save()

    def start(name, command, user='root', selected=None):
        unit = prefix + name
        claim_unit(unit)
        run(['systemd-run', '--quiet', '--unit', unit, '--slice', selected or pool.name,
             '-p', 'User=' + user, '-p', 'RuntimeMaxSec=180', '-p', 'TasksMax=16',
             '-p', 'MemoryMax=32M', '-p', 'OOMScoreAdjust=-1000', *command])
        return unit

    def check(unit):
        # The deployment operator is unprivileged and cannot read worker configuration.
        return run(['runuser', '-u', 'nobody', '--', '/usr/bin/python3', str(root / 'resource_pool.py'),
                    '--service', unit + '.service'], check=False)

    def sample():
        directory = Path('/sys/fs/cgroup') / properties(pool.name)['ControlGroup'].lstrip('/')
        return {name: (directory / name).read_text().strip() for name in
                ('memory.current', 'memory.max', 'memory.swap.max', 'memory.events', 'memory.stat',
                 'cgroup.stat', 'pids.max', 'cpu.max')}

    def value(sample, file, key):
        return int(dict(line.split() for line in sample[file].splitlines())[key])

    mounted = False
    root_created = False
    dropin_created = False
    save()
    try:
        assert not dropin.exists() and not dropin.is_symlink(), 'Probe drop-in must be new'
        root.mkdir(mode=0o755)
        root_created = True
        root.chmod(0o755)
        shutil.copyfile(Path(__file__).with_name('resource_pool.py'), root / 'resource_pool.py')
        (root / 'resource_pool.py').chmod(0o644)
        pool.start()
        keeper = start('keeper', ['/bin/sleep', '150'])
        state['supervisor'] = properties(keeper)
        result = check(keeper)
        state['unprivilegedPreflight'] = {'status': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr}
        assert result.returncode == 0, 'Finite root service must pass unprivileged preflight'
        other = start('outside', ['/bin/sleep', '150'], selected='system.slice')
        unprivileged = start('wrong-user', ['/bin/sleep', '150'], user='nobody')
        state['wrongMembershipStatus'] = check(other).returncode
        state['wrongRootIdentityStatus'] = check(unprivileged).returncode
        assert state['wrongMembershipStatus'] != 0 and state['wrongRootIdentityStatus'] != 0
        for unit in (other, unprivileged):
            run(['systemctl', 'stop', unit])
        run(['systemctl', 'set-property', '--runtime', pool.name, 'MemoryMax=infinity'])
        dropin_created = True
        state['unlimitedMemoryStatus'] = check(keeper).returncode
        assert state['unlimitedMemoryStatus'] != 0
        run(['systemctl', 'set-property', '--runtime', pool.name, 'MemoryMax=96M'])
        assert check(keeper).returncode == 0
        (root / 'memory').mkdir(mode=0o700)
        run(['mount', '-t', 'tmpfs', '-o', 'size=128m,nosuid,nodev,mode=0700', 'tmpfs', str(root / 'memory')])
        mounted = True
        writer = root / 'writer.py'
        writer.write_text('''import pathlib,sys
root=pathlib.Path(sys.argv[1]); name=sys.argv[2]; count=int(sys.argv[3])
print(pathlib.Path('/proc/self/cgroup').read_text(),flush=True)
with (root/'memory'/name).open('wb',buffering=0) as stream:
 for number in range(count):
  stream.write(bytes(1024*1024)); (root/(name+'-progress')).write_text(str(number+1))
''')
        state['baseline'] = sample()
        for name, count in [('A', 32), ('B', 80)]:
            unit = prefix + name.lower()
            claim_unit(unit)
            result = run(['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
                          '--slice', pool.name, '-p', 'User=root', '-p', 'MemoryMax=128M',
                          '-p', 'MemorySwapMax=0', '-p', 'TasksMax=16', '-p', 'CPUQuota=50%',
                          '-p', 'RuntimeMaxSec=25', '/usr/bin/python3', str(writer), str(root), name, str(count)], check=False)
            time.sleep(.5)
            snapshot = sample()
            state[name] = {'status': result.returncode, 'stdout': result.stdout, 'stderr': result.stderr,
                           'progressMiB': int((root / (name + '-progress')).read_text()),
                           'fileBytes': (root / 'memory' / name).stat().st_size, 'parent': snapshot}
            save()
            assert properties(pool.name)['ControlGroup'] + '/' + unit + '.service' in result.stdout
            if name == 'A':
                assert result.returncode == 0 and state[name]['fileBytes'] == 32 * 1024**2
                assert value(snapshot, 'memory.stat', 'shmem') >= 32 * 1024**2
                assert int(snapshot['memory.current']) >= 32 * 1024**2
            else:
                assert result.returncode != 0 and state[name]['progressMiB'] < 80
                assert value(snapshot, 'memory.events', 'oom_kill') > value(state['A']['parent'], 'memory.events', 'oom_kill')
                assert value(snapshot, 'memory.events', 'max') > value(state['A']['parent'], 'memory.events', 'max')
        journal = run(['journalctl', '-k', '--since', '@' + str(int(state['startedEpoch'])), '--no-pager', '-o', 'cat']).stdout
        state['poolOomLines'] = [line for line in journal.splitlines() if 'oom-kill:' in line and pool.name in line]
        assert state['poolOomLines'] and all('CONSTRAINT_MEMCG' in line for line in state['poolOomLines'])
        assert not any('oom-kill:' in line and 'CONSTRAINT_NONE' in line for line in journal.splitlines())
        state['result'] = 'PASS'
    except BaseException as error:
        state['result'] = 'FAIL'
        state['error'] = repr(error)
    finally:
        try:
            for unit in state['units']:
                run(['systemctl', 'stop', unit], check=False)
                run(['systemctl', 'reset-failed', unit], check=False)
            if mounted:
                run(['umount', str(root / 'memory')])
                state['afterUnmount'] = sample()
            pool.close()
            # set-property writes only this newly owned slice's runtime drop-in.
            if dropin_created and dropin.exists():
                for file in dropin.iterdir():
                    assert file.is_file() and not file.is_symlink()
                    file.unlink()
                dropin.rmdir()
                run(['systemctl', 'daemon-reload'])
            if root_created and root.exists():
                for file in root.iterdir():
                    assert not file.is_symlink()
                    file.rmdir() if file.is_dir() else file.unlink()
                root.rmdir()
            assert not run(['systemctl', 'list-units', '--all', '--no-legend', '--plain', prefix + '*']).stdout.strip()
            assert not root.exists() and not pool.path.exists() and not dropin.exists()
            state['cleanup'] = 'PASS'
        except BaseException as error:
            state['cleanup'] = 'FAIL'
            state['cleanupError'] = repr(error)
        state['finishedEpoch'] = time.time()
        save()
    print(json.dumps({'evidence': str(output), 'result': state['result'], 'cleanup': state.get('cleanup')}))
    return 0 if state['result'] == 'PASS' and state.get('cleanup') == 'PASS' else 1


if __name__ == '__main__':
    raise SystemExit(main())
