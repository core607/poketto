"""Independent bounded journal and kernel observations for retained-process acceptance."""
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess


def frame(path, expected=None):
    assert not path.is_symlink() and 48 <= path.stat().st_size <= 16 * 1024 * 1024
    raw = path.read_bytes()
    assert raw[:8] == b'PKRT0001' and struct.unpack('>q', raw[8:16])[0] == len(raw) - 48
    digest = hashlib.sha256(raw[16:-32]).digest()
    assert digest == raw[-32:] and (expected is None or digest.hex() == expected)
    return json.loads(raw[16:-32])


def journal(root, copy, account, workspace):
    matches = []
    paths = list((root / 'pool/metadata').glob('*.account'))
    assert len(paths) <= 128
    for path in paths:
        value = frame(path)
        if value['copyId'] == copy:
            assert value['format'] == 2
            assert value['owner']['accountId'] == account and value['owner']['workspaceId'] == workspace
            assert value['owner']['fullRead'] is True and re.fullmatch('[0-9a-f]{64}', path.stem)
            acknowledged = path.with_name(path.stem + '.acknowledged-' + value['state']['fileBaselines'])
            versions = frame(acknowledged, value['state']['fileBaselines'])
            assert versions['copyId'] == copy
            original = path.with_suffix('.baseline')
            assert not original.is_symlink() and original.stat().st_size == value['original']['bytes'] <= 64 * 1024 * 1024
            digest = hashlib.sha256(original.read_bytes()).hexdigest()
            assert digest == value['original']['sha256']
            matches.append({'record': value, 'baselines': versions['files'], 'originalSha256': digest})
    assert len(matches) == 1, 'Expected one exact account/copy journal'
    return matches[0]


def preserved(before, after, interrupted):
    old, current = before['record'], after['record']
    for key in ('owner', 'copyId', 'state', 'original', 'publicExport'):
        assert current[key] == old[key], 'Process recovery changed retained ' + key
    assert after['baselines'] == before['baselines'] and after['originalSha256'] == before['originalSha256']
    assert current['phase'] == 'READY' and current['executionId'] is None
    assert current['revision'] > old['revision'] and current['expiresAt'] >= old['expiresAt']
    assert current['writer']['appBootId'] != old['writer']['appBootId']
    assert current['writer']['workerBootId'] == old['writer']['workerBootId']
    assert current['writer']['principalId'] == old['writer']['principalId']
    assert current['writer']['leaseId'] != old['writer']['leaseId']
    assert current['writer']['storage'] == old['writer']['storage']
    assert current['lastInterruptedCommand'] == (old['executionId'] if interrupted else old['lastInterruptedCommand'])


def native(settings, domain, operation=None, identifier=None):
    command = ['runuser', '-u', settings['appUser'], '--', settings['nativeBinary'], domain]
    command += [value for value in (operation, identifier) if value is not None]
    command += ['--socket', settings['nativeSocket']]
    result = subprocess.run(command, check=True, capture_output=True, text=True, timeout=20)
    assert len(result.stdout) <= 1024 * 1024
    return json.loads(result.stdout)


def descendants(settings, session, marker):
    # The public native implementation gives all root-session shells one bounded child slice.
    namespace = hashlib.sha256((settings['nativeUnit'] + '-').encode()).hexdigest()
    unit = settings['nativeSlice'].removesuffix('.slice') + '-sandcrab' + namespace + session.replace('-', '') + '.slice'
    result = subprocess.run(['systemctl', 'show', unit, '-p', 'ControlGroup', '--value'],
                            check=True, capture_output=True, text=True, timeout=10)
    group = result.stdout.strip()
    assert group.startswith('/') and '..' not in Path(group).parts
    directory = Path('/sys/fs/cgroup') / group.lstrip('/')
    pids = {int(pid) for path in directory.rglob('cgroup.procs') for pid in path.read_text().split()}
    assert pids and len(pids) <= 128
    rows = []
    for pid in sorted(pids):
        process = Path('/proc') / str(pid)
        try:
            command = (process / 'cmdline').read_bytes()
            if marker.encode() not in command.split(b'\0'):
                continue
            start = (process / 'stat').read_text().rsplit(')', 1)[1].split()[19]
            assert os.getsid(pid) == pid, 'Expected a detached guest descendant'
            rows.append({'pid': pid, 'startTicks': start, 'commandSha256': hashlib.sha256(command).hexdigest()})
        except FileNotFoundError:
            continue
    assert len(rows) == 1, 'Expected one live marked guest descendant inside the old native session'
    return {'unit': unit, 'cgroup': group, 'processes': rows}


def gone(observation):
    directory = Path('/sys/fs/cgroup') / observation['cgroup'].lstrip('/')
    if directory.exists():
        fields = dict(line.split() for line in (directory / 'cgroup.events').read_text().splitlines())
        assert fields['populated'] == '0', 'Old native session descendants remain live'
    for row in observation['processes']:
        process = Path('/proc') / str(row['pid']) / 'stat'
        if process.exists():
            assert process.read_text().rsplit(')', 1)[1].split()[19] != row['startTicks']
