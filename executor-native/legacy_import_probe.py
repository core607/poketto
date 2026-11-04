#!/usr/bin/env python3
"""Exercise the offline controller against a live disposable Docker, systemd, Git and bounded XFS installation."""
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import sys
import time

from legacy_import_fixture import LegacyFixture, call
from overlay_copy_probe import argument_parser, api, native_service


def snapshot(root):
    """Compare actual bytes, modes, symlinks and hardlink relationships without reusing archive extraction."""
    rows, hardlinks = {}, {}
    for area in ('work', 'home'):
        base = root / area
        assert base.is_dir() and not base.is_symlink()
        for path in [base, *sorted(base.rglob('*'))]:
            info = path.lstat()
            name = path.relative_to(root).as_posix()
            row = {'mode': stat.S_IMODE(info.st_mode), 'uid': info.st_uid, 'gid': info.st_gid}
            if stat.S_ISDIR(info.st_mode):
                row['kind'] = 'directory'
            elif stat.S_ISLNK(info.st_mode):
                row.update(kind='symlink', target=str(path.readlink()))
            elif stat.S_ISREG(info.st_mode):
                data = path.read_bytes()
                assert len(data) <= 1024 * 1024, 'Synthetic migration file exceeded its bound'
                row.update(kind='file', bytes=len(data), sha256=hashlib.sha256(data).hexdigest())
                if info.st_nlink > 1:
                    hardlinks.setdefault((info.st_dev, info.st_ino), []).append(name)
            else:
                raise AssertionError('Synthetic migration contains an unsupported node')
            rows[name] = row
    return {'files': rows, 'hardlinks': sorted(sorted(names) for names in hardlinks.values())}


def journal_preserved(before, after):
    assert after.keys() == before.keys(), 'Migration changed the journal field set'
    assert before['writer']['storage'] is None
    for key in before:
        if key not in ('writer', 'revision'):
            assert after[key] == before[key], 'Migration changed retained journal field: ' + key
    assert after['revision'] == before['revision'] + 1
    assert after['writer']['principalId'] == before['writer']['principalId']
    assert after['writer']['storage']['volumeId'] == before['copyId']
    assert before['state']['originalCommit'] != before['state']['baseCommit']
    assert before['state']['lastSave'] and before['state']['lastImport']
    assert before['lastInterruptedCommand'] is not None
    kinds = {value['content']['kind'] for value in before['state']['fileBaselines'].values()}
    assert kinds == {'text', 'nontext', 'absent'}


def invoke(legacy, phase, execute=False, refusal=None):
    command = [sys.executable, '-I', str(legacy.args.operator / 'migrate_native_repository.py'),
               '--request', str(legacy.request_path), '--operation', str(legacy.operation)]
    if execute:
        command.append('--execute')
    result = subprocess.run(command, stdin=subprocess.DEVNULL, capture_output=True, timeout=150)
    (legacy.args.output / (phase + '.stdout')).write_bytes(result.stdout)
    (legacy.args.output / (phase + '.stderr')).write_bytes(result.stderr)
    if refusal is not None:
        assert result.returncode != 0 and json.loads(result.stderr)['reason'] == refusal, phase
    else:
        assert result.returncode == 0, phase
        assert json.loads(result.stdout) == {'status': 'published' if execute else 'prepared',
                                            'operation': legacy.request['operation'], 'sourceRetained': True}, phase


def check_import(legacy, socket, args, storage, receipt):
    invoke(legacy, 'active-app', refusal='APPLICATION_ACTIVE')
    call('docker', 'stop', '--time', '5', legacy.container)
    invoke(legacy, 'active-writer', refusal='LEGACY_WRITER_ACTIVE')
    legacy.stop_legacy()
    call('mount', '--bind', str(legacy.source / 'work'), str(legacy.mount))
    try:
        invoke(legacy, 'still-mounted', refusal='SOURCE_STILL_MOUNTED')
    finally:
        call('umount', str(legacy.mount))
    receipt['checks'].append('active-app-running-systemd-writer-and-real-bind-mount-each-refused')
    original = snapshot(legacy.source)
    invoke(legacy, 'prepare')
    assert legacy.journal('read') == legacy.before and legacy.metadata_snapshot() == legacy.metadata_files
    assert api(socket, args, 'GET', 'volumes')[1]['volumes'] == []
    receipt['checks'].append('prepare-pinned-archives-without-native-volume-or-journal-mutation')
    invoke(legacy, 'execute', execute=True)
    plan = json.loads((legacy.operation / 'plan.json').read_text())
    after = legacy.journal('read')
    journal_preserved(legacy.before, after)
    assert after['writer'] == plan['writer']
    metadata = legacy.metadata_snapshot()
    assert metadata.keys() == legacy.metadata_files.keys()
    changed = [key for key, value in metadata.items() if legacy.metadata_files[key] != value]
    assert len(changed) == 1 and changed[0].endswith('.account')
    destination = storage / 'data/volumes' / legacy.copy / 'data'
    assert snapshot(legacy.source) == original == snapshot(destination)
    assert {path.name for path in destination.iterdir()} == {'work', 'home'}
    assert api(socket, args, 'GET', 'volumes/' + legacy.copy)[1]['state'] == 'ready'
    receipt['checks'].append('legacy-git-work-home-link-topology-and-all-retained-state-survived-cutover')
    invoke(legacy, 'replay', execute=True)
    assert legacy.journal('read') == after and snapshot(destination) == original
    for part in ('work', 'home'):
        assert api(socket, args, 'GET', 'uploads/' + plan[part]['upload']['id'])[0] == 404
    volume = 'volumes/' + legacy.copy
    assert api(socket, args, 'POST', volume + '/delete', {})[0] in (200, 202)
    deadline = time.monotonic() + 20
    while api(socket, args, 'GET', volume)[1]['state'] != 'deleted':
        if time.monotonic() >= deadline:
            raise TimeoutError('Owned volume deletion did not reach its terminal state')
        time.sleep(.1)
    assert api(socket, args, 'POST', volume + '/forget', {}) == (200, {'forgotten': True})
    assert api(socket, args, 'GET', 'volumes')[1]['volumes'] == []
    receipt['checks'].append('fixed-plan-replay-preserved-the-journal-and-owned-resources-were-released')
    receipt['comparedEntries'] = len(original['files'])
    receipt['journalRevisions'] = [legacy.before['revision'], after['revision']]


def main():
    parser = argument_parser(__doc__)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--container-image', required=True, help='An already present Docker image ID with /bin/sleep')
    parser.add_argument('--operator', required=True, type=Path)
    args = parser.parse_args()
    app = pwd.getpwnam('daemon')
    if (not re.fullmatch('[0-9a-f]{40}', args.revision)
            or not re.fullmatch('sha256:[0-9a-f]{64}', args.container_image)
            or (args.uid, args.gid) != (app.pw_uid, app.pw_gid)):
        parser.error('Use the exact revision, local image ID, and the disposable daemon application account')
    args.operator = args.operator.resolve(strict=True)
    sys.path.insert(0, str(args.operator))
    args.report = args.output.absolute() / 'summary.json'
    receipt = {'result': 'FAIL', 'cleanup': 'NOT_STARTED', 'checks': [],
               'scope': 'Production controller, actual Sandcrab, kernel project quotas, systemd and Docker; synthetic user data.'}
    with native_service(args, receipt, volumes=True) as (socket, _, storage):
        assert api(socket, args, 'GET', 'info')[1]['storage']['quotaEnforced'] is True
        root = storage.parent / 'legacy'
        root.mkdir(mode=0o755)
        legacy = LegacyFixture(root, socket, args)
        try:
            legacy.setup()
            check_import(legacy, socket, args, storage, receipt)
        finally:
            try:
                legacy.close()
            finally:
                if os.path.ismount(legacy.pool):
                    call('umount', str(legacy.pool))


if __name__ == '__main__':
    main()
