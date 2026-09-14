#!/usr/bin/env python3
"""Real login, PostgreSQL, HTTP MCP and native SRT acceptance for shared account copies."""
import argparse
import json
import os
from pathlib import Path
import stat
import time

from mcp_http import Browser, Mcp, passed


def verify(browser, left, right, public):
    tools = left.rpc('tools/list', {})['tools']
    execution = next(tool for tool in tools if tool['name'] == 'repo_exec')['inputSchema']
    disposal = next(tool for tool in tools if tool['name'] == 'repo_discard')['inputSchema']
    assert 'resume' not in execution['properties'] and 'expectedGeneration' not in execution['properties']
    assert disposal['required'] == ['expectedCopyId']
    first = left.call('repo_exec', {'expectedCopyId': 'new', 'command':
        "set -eu; printf before > private/draft.md; "
        "python3 -c \"from pathlib import Path; Path('private/draft.bin').write_bytes(bytes([0,255]))\""})
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
    saved = left.call('repo_exec', {'expectedCopyId': copy, 'command': 'poketto save private/draft.md'})
    assert saved['exitCode'] == 0 and json.loads(saved['stdout'])['ok']
    authoritative = browser.file('private/draft.md')
    assert authoritative['source'] == 'beforepartial'
    passed('account-http-timeout-preserves-work-and-save-is-visible-through-authoritative-readback')
    verify_remote_status(browser, left, copy, authoritative)
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


def verify_remote_status(browser, client, copy, saved):
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    root = parser.parse_args().fixture.resolve(strict=True)
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
        verify(browser, left, right, public)
        print(json.dumps({'accountHttp': 'PASS', 'tests': 6,
                          'source': 'real-auth-PG-HTTP-MCP-native-SRT', 'modelDriven': False}), flush=True)
    finally:
        (root / 'stop').touch()


if __name__ == '__main__':
    main()
