#!/usr/bin/env python3
"""Real login, PostgreSQL, HTTP MCP and native SRT acceptance for shared account copies."""
import argparse
import fcntl
import hashlib
import subprocess
import threading
import json
import os
from pathlib import Path
import stat
import time

from mcp_http import Browser, Mcp, passed


def verify(browser, left, right, public, root):
    tools = left.rpc('tools/list', {})['tools']
    execution = next(tool for tool in tools if tool['name'] == 'repo_exec')['inputSchema']
    disposal = next(tool for tool in tools if tool['name'] == 'repo_discard')['inputSchema']
    assert 'resume' not in execution['properties'] and 'expectedGeneration' not in execution['properties']
    assert disposal['required'] == ['expectedCopyId']
    contention = index_contention(root)
    first = left.call('repo_exec', {'expectedCopyId': 'new', 'command':
        "set -eu; printf before > private/draft.md; "
        "python3 -c \"from pathlib import Path; Path('private/draft.bin').write_bytes(bytes([0,255]))\""})
    contention.join(3)
    assert contention.held and not contention.is_alive(), 'index contention probe did not complete'
    passed('account-http-copy-initialization-waits-for-a-contended-journal-index')
    copy = first['copyId']
    assert first['exitCode'] == 0 and first['retention']['expiresAt'] > int(time.time() * 1000)
    same = right.call('repo_exec', {'expectedCopyId': 'new', 'command': 'cat private/draft.md'})
    assert same['copyId'] == copy and same['commit'] == first['commit'] and same['stdout'] == 'before'
    denied = public.call('repo_exec', {'expectedCopyId': copy, 'command': 'cat private/draft.md'}, error=True)
    assert denied['executed'] is False and denied['reason'] == 'MISSING_COPY'
    passed('account-http-new-transports-and-grants-share-work-without-private-scope-escalation')
    timeout = left.call('repo_exec', {'expectedCopyId': copy, 'timeoutSeconds': 2, 'command':
        'printf partial >> private/draft.md; (sleep 40; touch private/late.md) & wait'})
    assert timeout['timedOut'] and timeout['terminationReason'] == 'TIMEOUT' and timeout['copyId'] == copy
    checked = right.call('repo_exec', {'expectedCopyId': copy, 'command':
        "set -eu; test \"$(cat private/draft.md)\" = beforepartial; test ! -e private/late.md; "
        "python3 -c \"from pathlib import Path; assert Path('private/draft.bin').read_bytes() == bytes([0,255])\""})
    assert checked['exitCode'] == 0 and checked['commit'] == first['commit']
    saved = left.call('repo_exec', {'expectedCopyId': copy, 'command': 'set -eu; git add private/draft.md; git -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm local-checkpoint; git rev-parse HEAD > private/local-checkpoint-id; poketto save private/draft.md'})
    assert saved['exitCode'] == 0 and json.loads(saved['stdout'])['ok']
    verify_git_baseline(left, right, copy, saved)
    authoritative = browser.file('private/draft.md')
    assert authoritative['source'] == 'beforepartial'
    passed('account-http-timeout-preserves-work-and-save-is-visible-through-authoritative-readback')
    verify_remote_status(browser, left, copy, authoritative, root)
    projection = public.call('repo_exec', {'expectedCopyId': 'new', 'command':
        'set -eu; test ! -e private; printf scoped > public-draft'})
    assert projection['exitCode'] == 0 and projection['copyId'] != copy
    scoped = left.call('repo_exec', {'expectedCopyId': projection['copyId'], 'command':
        'set -eu; test ! -e private; cat public-draft'})
    assert scoped['exitCode'] == 0 and scoped['stdout'] == 'scoped' and scoped['copyId'] == projection['copyId']
    public_status = left.call('repo_exec', {'expectedCopyId': projection['copyId'], 'command': 'poketto status'})
    assert public_status['exitCode'] == 0
    assert json.loads(public_status['stdout'])['result']['remote'] == {
        'state': 'PUBLIC_PROJECTION', 'commit': projection['commit']}
    assert projection['commit'] != browser.file('private/draft.md')['commit']
    assert public.call('repo_discard', {'expectedCopyId': projection['copyId']})['status'] == 'DISCARDED'
    passed('account-http-public-copy-keeps-its-scope-when-accessed-by-a-full-grant')
    issued = browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])
    revoked = Mcp(browser.endpoint, issued['token'])
    draft = revoked.call('repo_exec', {'expectedCopyId': copy, 'command': 'printf kept > private/revoked.md'})
    assert draft['exitCode'] == 0
    browser.api('DELETE', browser.admin + '/keys/' + issued['id'], expected=204)
    assert revoked.initialize(expected=401) is None
    survivor = left.call('repo_exec', {'expectedCopyId': copy, 'command': 'cat private/revoked.md'})
    assert survivor['copyId'] == copy and survivor['stdout'] == 'kept'
    passed('account-http-revocation-denies-the-grant-without-deleting-account-work')
    assert right.call('repo_discard', {'expectedCopyId': copy})['status'] == 'DISCARDED'
    assert left.call('repo_discard', {'expectedCopyId': copy})['status'] == 'ABSENT'
    fresh = left.call('repo_exec', {'expectedCopyId': 'new', 'command':
        'set -eu; test ! -e private/draft.bin; test ! -e private/late.md; cat private/draft.md'})
    assert fresh['exitCode'] == 0 and fresh['copyId'] != copy and fresh['stdout'] == 'beforepartial'
    assert browser.file('private/draft.md')['source'] == authoritative['source']
    assert left.call('repo_discard', {'expectedCopyId': fresh['copyId']})['status'] == 'DISCARDED'
    passed('account-http-shared-disposal-clears-only-local-work-and-retains-saved-authority')


def verify_git_baseline(left, right, copy, saved):
    acknowledged = json.loads(saved['stdout'])['result']['commit']
    assert saved['commit'] == acknowledged
    continued = right.call('repo_exec', {'expectedCopyId': copy, 'command': 'set -eu; git cat-file -e "$(cat private/local-checkpoint-id)^{commit}"; git rev-parse HEAD; git status --porcelain'})
    assert continued['exitCode'] == 0 and continued['stdout'].splitlines()[0] == acknowledged
    assert 'private/draft.md' not in continued['stdout'] and 'private/draft.bin' in continued['stdout']
    second = left.call('repo_exec', {'expectedCopyId': copy, 'command': """set -eu
printf second > private/second-saved.md
printf index-only > private/staged.md
git add private/staged.md
printf work-only > private/staged.md
poketto save private/second-saved.md > /tmp/save-result
python3 - <<'PY'
import json, subprocess
saved = json.load(open('/tmp/save-result'))
assert saved['ok'], saved
assert subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip() == saved['result']['commit']
status = subprocess.check_output(['git', 'status', '--porcelain'], text=True)
assert 'private/second-saved.md' not in status and 'private/draft.md' not in status
assert 'private/draft.bin' in status
assert subprocess.check_output(['git', 'show', 'stash@{0}^2:private/staged.md'], text=True) == 'index-only'
assert open('private/staged.md').read() == 'work-only'
print(json.dumps(saved))
PY
"""})
    assert second['exitCode'] == 0, second
    assert second['commit'] == json.loads(second['stdout'])['result']['commit'] != acknowledged
    passed('account-http-consecutive-saves-advance-head-index-and-tool-commit-without-saving-binary-drafts')


def verify_remote_status(browser, client, copy, saved, root):
    def status():
        result = client.call('repo_exec', {'expectedCopyId': copy, 'command': 'poketto status'})
        assert result['exitCode'] == 0
        reply = json.loads(result['stdout'])
        assert reply['ok']
        return reply['result']

    before = status()
    assert before['baseCommit'] == saved['commit']
    assert before['remote'] == {'state': 'MATCHES_BASE', 'commit': saved['commit']}
    draft = client.call('repo_exec', {'expectedCopyId': copy, 'command':
        "printf unsaved > private/local-only.md"})
    assert draft['exitCode'] == 0
    changed = browser.api('POST', browser.admin + '/repository/patch', {
        'baseCommit': saved['commit'], 'changes': [{'path': 'private/browser-only.md',
            'expectedAbsence': True, 'content': 'from browser'}]})
    assert changed['committed'] and changed['commit'] != saved['commit']
    after = status()
    assert after['baseCommit'] == before['baseCommit'] and after['lastSave'] == before['lastSave']
    assert after['remote'] == {'state': 'DIFFERS_FROM_BASE', 'commit': changed['commit']}
    untouched = client.call('repo_exec', {'expectedCopyId': copy, 'command':
        "set -eu; test ! -e private/browser-only.md; "
        "test \"$(cat private/draft.md)\" = beforepartial; cat private/local-only.md"})
    assert untouched['exitCode'] == 0 and untouched['stdout'] == 'unsaved'
    passed('account-http-status-detects-browser-write-without-changing-local-work-or-save-base')
    removed = browser.file('private/second-saved.md')
    latest = browser.api('POST', browser.admin + '/repository/patch', {
        'baseCommit': changed['commit'], 'changes': [{'path': 'private/second-saved.md',
            'expectedAbsence': False, 'expectedRevision': removed['revision'], 'content': None}]})
    synced = client.call('repo_exec', {'expectedCopyId': copy, 'timeoutSeconds': 60,
        'command': 'poketto sync'})
    assert synced['exitCode'] == 0, synced
    reply = json.loads(synced['stdout'])
    assert reply['ok'] and reply['code'] == 'SYNCHRONIZED', reply
    assert synced['commit'] == latest['commit']
    checked = client.call('repo_exec', {'expectedCopyId': copy, 'command':
        "set -eu; test ! -e private/second-saved.md; test \"$(cat private/local-only.md)\" = unsaved; "
        "test \"$(cat private/browser-only.md)\" = 'from browser'; "
        "git diff --cached --quiet; poketto status"})
    assert checked['exitCode'] == 0, checked
    status = json.loads(checked['stdout'])['result']
    assert not status['syncPending'] and not status['localBaselinePending']
    assert status['baseCommit'] == latest['commit']
    passed('account-http-workspace-sync-discovers-additions-deletions-and-preserves-unsaved-files')
    verify_sync_conflicts(browser, client, copy, root)


def verify_sync_conflicts(browser, client, copy, root):
    original = bytes([0, 255, 1, 2])
    commit_binary(root, original)
    binary = client.call('repo_exec', {'expectedCopyId': copy, 'timeoutSeconds': 60,
        'command': "poketto sync && python3 -c \"from pathlib import Path; assert Path('private/remote-sync.bin').read_bytes() == bytes([0,255,1,2])\""})
    assert binary['exitCode'] == 0, binary
    local = client.call('repo_exec', {'expectedCopyId': copy, 'command':
        "python3 -c \"from pathlib import Path; Path('private/remote-sync.bin').write_bytes(bytes([0,255,9]))\""})
    assert local['exitCode'] == 0
    commit_binary(root, bytes([0, 255, 3, 4]))
    binary_conflict = client.call('repo_exec', {'expectedCopyId': copy, 'timeoutSeconds': 60, 'command': 'poketto sync'})
    reply = json.loads(binary_conflict['stdout'])
    assert binary_conflict['exitCode'] == 1 and reply['code'] == 'MERGE_CONFLICT', binary_conflict
    assert 'private/remote-sync.bin' in reply['result']['conflicts']
    kept = client.call('repo_exec', {'expectedCopyId': copy, 'command':
        "python3 -c \"from pathlib import Path; assert Path('private/remote-sync.bin').read_bytes() == bytes([0,255,9])\""})
    assert kept['exitCode'] == 0
    passed('account-http-workspace-sync-installs-remote-binary-and-preserves-conflicting-local-bytes')
    assert client.call('repo_exec', {'expectedCopyId': copy, 'command':
        "printf 'local text' > private/browser-only.md"})['exitCode'] == 0
    observed = browser.file('private/browser-only.md')
    competing = browser.api('POST', browser.admin + '/repository/patch', {
        'baseCommit': observed['commit'], 'changes': [{'path': 'private/browser-only.md',
            'expectedAbsence': False, 'expectedRevision': observed['revision'], 'content': 'remote text'}]})
    conflict = client.call('repo_exec', {'expectedCopyId': copy, 'timeoutSeconds': 60, 'command': 'poketto sync'})
    reply = json.loads(conflict['stdout'])
    assert conflict['exitCode'] == 1 and reply['code'] == 'MERGE_CONFLICT', conflict
    assert conflict['commit'] == competing['commit'] and 'private/browser-only.md' in reply['result']['conflicts']
    versions = client.call('repo_exec', {'expectedCopyId': copy, 'command': 'cat private/browser-only.md'})
    assert all(text in versions['stdout'] for text in ('<<<<<<< LOCAL', '||||||| BASE', 'local text', 'from browser', 'remote text'))
    assert browser.file('private/browser-only.md')['source'] == 'remote text'
    passed('account-http-workspace-sync-conflicts-retain-all-text-versions-without-saving')



def index_contention(root):
    metadata = root / 'pool/metadata'
    def hold():
        deadline = time.monotonic() + 30
        while not list(metadata.glob('.original-*')):
            if time.monotonic() >= deadline:
                return
            time.sleep(.002)
        descriptor = os.open(metadata / '.index.lock', os.O_RDWR | os.O_NOFOLLOW)
        try:
            fcntl.flock(descriptor, fcntl.LOCK_EX)
            thread.held = True
            time.sleep(.5)
        finally:
            os.close(descriptor)
    thread = threading.Thread(target=hold, daemon=True)
    thread.held = False
    thread.start()
    return thread


def commit_binary(root, content):
    seed = root / 'content/seed'
    assert seed.resolve().is_relative_to(root) and seed.is_dir()
    owner = seed.stat()
    environment = {'PATH': '/usr/bin:/bin', 'HOME': str(root / 'home'),
                   'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_NOSYSTEM': '1'}
    def git(*arguments):
        result = subprocess.run(['setpriv', '--reuid=' + str(owner.st_uid), '--regid=' + str(owner.st_gid),
            '--clear-groups', 'git', '-C', str(seed), *arguments], env=environment,
            capture_output=True, check=True, timeout=30)
        return result.stdout
    remote = root / 'content/remote.git'
    assert remote.resolve().is_relative_to(root) and remote.is_dir()
    git('fetch', str(remote), 'main')
    git('reset', '--hard', 'FETCH_HEAD')
    target = seed / 'private/remote-sync.bin'
    target.write_bytes(content)
    os.chown(target, owner.st_uid, owner.st_gid)
    git('add', '--', 'private/remote-sync.bin')
    git('-c', 'user.name=Acceptance', '-c', 'user.email=acceptance@example.invalid',
        'commit', '-qm', 'Update synthetic binary fixture')
    git('push', str(remote), 'HEAD:main')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--keep', action='store_true', help='Keep this isolated fixture for follow-up probes until its controller expires')
    args = parser.parse_args()
    root = args.fixture.resolve(strict=True)
    assert os.geteuid() == 0 and root.parent == Path('/var/lib') and root.name.startswith('poketto-client-')
    info = (root / 'client.json').stat()
    assert info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600
    data = json.loads((root / 'client.json').read_text())
    assert data['endpoint'].startswith('http://127.0.0.1:')
    try:
        browser = Browser(data['endpoint'], data['password'])
        full = ['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY']
        left = Mcp(browser.endpoint, browser.key(full)['token'])
        right = Mcp(browser.endpoint, browser.key(full)['token'])
        public = Mcp(browser.endpoint, browser.key(['EXECUTE_REPOSITORY'])['token'])
        verify(browser, left, right, public, root)
        print(json.dumps({'accountHttp': 'PASS', 'tests': 11,
                          'source': 'real-auth-PG-HTTP-MCP-native-SRT', 'modelDriven': False}), flush=True)
    finally:
        if not args.keep:
            (root / 'stop').touch()


if __name__ == '__main__':
    main()
