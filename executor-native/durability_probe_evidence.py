"""Independent disk and native-state observations at the five process-loss barriers."""
import hashlib
import json
from pathlib import Path
import re
import stat
import struct

SCENARIOS = ('uncertain', 'beforepublish', 'afterpublish', 'discarding', 'expired')


def ordinary(path, maximum):
    info = path.lstat()
    assert stat.S_ISREG(info.st_mode) and 0 < info.st_size <= maximum
    return path.read_bytes()


def json_file(path, maximum=16 * 1024 * 1024):
    return json.loads(ordinary(path, maximum))


def frame(path, expected=None):
    raw = ordinary(path, 16 * 1024 * 1024)
    assert len(raw) >= 48 and raw[:8] == b'PKRT0001'
    assert struct.unpack('>q', raw[8:16])[0] == len(raw) - 48
    digest = hashlib.sha256(raw[16:-32]).digest()
    assert digest == raw[-32:] and (expected is None or expected == digest.hex())
    return json.loads(raw[16:-32])


def disk_checkpoint(directory, before):
    paths = list(directory.glob('*.account'))
    assert len(paths) == 1 and re.fullmatch('[0-9a-f]{64}', paths[0].stem)
    path = paths[0]
    stored = frame(path)
    assert stored['format'] == 2
    for key in before:
        if key != 'state':
            assert stored[key] == before[key], 'Disk publication differs from its barrier: ' + key
    for key in stored['state']:
        if key != 'fileBaselines':
            assert stored['state'][key] == before['state'][key]
    versions = frame(path.with_name(path.stem + '.acknowledged-' + stored['state']['fileBaselines']),
                     stored['state']['fileBaselines'])
    assert versions['copyId'] == before['copyId'] and versions['files'] == before['state']['fileBaselines']
    original = path.with_suffix('.baseline')
    raw = ordinary(original, 64 * 1024 * 1024)
    assert len(raw) == before['original']['bytes']
    assert hashlib.sha256(raw).hexdigest() == before['original']['sha256']
    return {'journalSha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'acknowledgedSha256': stored['state']['fileBaselines'],
            'originalSha256': before['original']['sha256'], 'phase': stored['phase'],
            'revision': stored['revision'], 'copyId': stored['copyId']}


def barrier(ready, before, scenario, pid, epoch, classes):
    assert scenario in SCENARIOS and ready['scenario'] == scenario and ready['pid'] == pid
    assert ready['probeClassSha256'] == classes['probe'] and ready['executorClassSha256'] == classes['executor']
    assert ready['writer'] == before['writer'] and ready['copyId'] == before['copyId']
    assert ready['writer']['workerBootId'] == epoch
    assert ready['writer']['storage']['volumeId'] == ready['copyId']
    phase = {'uncertain': 'RUNNING', 'beforepublish': 'RUNNING', 'afterpublish': 'READY',
             'discarding': 'DISCARDING', 'expired': 'READY'}[scenario]
    assert ready['phase'] == before['phase'] == phase
    assert ready['executionId'] == before['executionId']
    if phase == 'RUNNING':
        assert ready['commandId'] == before['executionId'] and ready['commandId'] is not None
    else:
        assert before['executionId'] is None
    if scenario == 'uncertain':
        assert before['state']['uncertain'] and ready['candidateCommit'] == before['state']['attempt']['commit']
        assert ready['candidateCommit'] != before['state']['baseCommit']
    else:
        assert ready['candidateCommit'] is None


def native_checkpoint(scenario, ready, session, volume, run):
    assert volume['id'] == ready['copyId']
    if scenario == 'discarding':
        assert volume['state'] == 'deleted'
        assert session is None or session['state'] in ('closed', 'expired', 'failed')
        assert not volume['busy'] and volume['attachedTo'] is None
        return
    assert session['id'] == ready['writer']['leaseId'] and session['serverId'] == ready['writer']['workerBootId']
    assert volume['state'] == 'ready' and volume['attachedTo'] == session['id']
    if scenario == 'uncertain':
        assert session['state'] == 'busy' and session['activeRunId'] == ready['commandId']
        assert run['id'] == ready['commandId'] and run['state'] == 'running'
    elif scenario in ('beforepublish', 'afterpublish'):
        assert session['state'] == 'ready' and session['activeRunId'] is None
        assert run['id'] == ready['commandId'] and run['state'] == 'succeeded' and run['result']['exitCode'] == 0
    else:
        assert session['state'] == 'ready' and session['activeRunId'] is None
