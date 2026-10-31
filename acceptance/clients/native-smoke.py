#!/usr/bin/env python3
"""Real account, HTTP MCP and native-volume checks before the small judged load."""
import argparse
import json
import os
from pathlib import Path
import stat
import subprocess
import uuid

from mcp_http import Browser, Mcp


def native(settings, domain, operation, *arguments):
    result = subprocess.run(['runuser', '-u', settings['appUser'], '--', settings['nativeBinary'],
                             domain, operation, '--socket', settings['nativeSocket'], *arguments],
                            check=True, capture_output=True, text=True, timeout=20)
    assert len(result.stdout) <= 1024 * 1024, 'Native fixture response exceeded its bound'
    return json.loads(result.stdout)


def absent(browser, path, commit):
    observed = browser.file(path)
    assert observed['expectedAbsence'] is True and observed['source'] is None and observed['revision'] is None
    assert observed['path'] == path and observed['commit'] == commit


def verify(settings, checks):
    browser = Browser(settings['endpoint'], settings['password'])
    full = ['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY']
    left_key, right_key = browser.key(full), browser.key(full)
    restricted_key = browser.key(['READ_PRIVATE'])
    left, right = Mcp(browser.endpoint, left_key['token']), Mcp(browser.endpoint, right_key['token'])
    restricted = Mcp(browser.endpoint, restricted_key['token'])
    issued = [left_key, right_key, restricted_key]
    try:
        # Discovery is stable; current grants are enforced when the tool is called.
        before_denial = native(settings, 'volume', 'list')
        denied = restricted.call('repo_exec', {'expectedCopyId': 'new', 'command': 'printf forbidden'}, error=True)
        assert denied['code'] == 'DENIED'
        assert native(settings, 'volume', 'list') == before_denial
        checks.append('real-login-and-key-capability-control')
        first = left.call('repo_exec', {'expectedCopyId': 'new', 'command':
            'set -eu; test -d .git; printf native-retained > private/native-draft.md; '
            'printf local-only > private/native-scratch.md; printf initialized'})
        copy = str(uuid.UUID(first['copyId']))
        assert first['exitCode'] == 0 and first['stdout'] == 'initialized'
        volume = native(settings, 'volume', 'get', copy)
        assert volume['id'] == copy and volume['state'] == 'ready' and volume['profile'] == 'repository'
        assert volume['attachedTo'] is not None and volume['byteLimit'] == 64 * 1024 * 1024
        checks.append('mcp-copy-is-an-actual-bounded-native-volume')
        continued = right.call('repo_exec', {'expectedCopyId': copy, 'command':
            'set -eu; test "$(cat private/native-scratch.md)" = local-only; cat private/native-draft.md'})
        assert continued['copyId'] == copy and continued['exitCode'] == 0 and continued['stdout'] == 'native-retained'
        checks.append('independent-http-transports-and-grants-share-account-copy-bytes')
        browser.api('DELETE', browser.admin + '/keys/' + right_key['id'], expected=204)
        issued.remove(right_key)
        assert right.initialize(expected=401) is None
        saved = left.call('repo_exec', {'expectedCopyId': copy, 'command': 'poketto save private/native-draft.md'})
        save_receipt = json.loads(saved['stdout'])
        assert saved['exitCode'] == 0 and save_receipt['ok']
        assert saved['commit'] == first['commit'], 'MCP copy identity must retain its original commit'
        authoritative = browser.file('private/native-draft.md')
        assert authoritative['source'] == 'native-retained'
        assert authoritative['commit'] == save_receipt['result']['commit']
        absent(browser, 'private/native-scratch.md', save_receipt['result']['commit'])
        checks.append('revocation-preserves-copy-and-selected-save-has-authoritative-http-readback')
        removed = left.call('repo_exec', {'expectedCopyId': copy, 'command':
            'rm private/native-draft.md && poketto save --delete private/native-draft.md'})
        assert removed['exitCode'] == 0
        absent(browser, 'private/native-draft.md', json.loads(removed['stdout'])['result']['commit'])
        assert left.call('repo_discard', {'expectedCopyId': copy})['status'] == 'DISCARDED'
        assert left.call('repo_discard', {'expectedCopyId': copy})['status'] == 'ABSENT'
        assert copy not in {volume['id'] for volume in native(settings, 'volume', 'list')['volumes']}
        checks.append('selected-delete-and-idempotent-discard-release-the-native-volume')
    finally:
        for key in issued:
            browser.api('DELETE', browser.admin + '/keys/' + key['id'], expected=204)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    root = args.fixture.resolve(strict=True)
    assert os.geteuid() == 0 and root.parent == Path('/var/lib') and root.name.startswith('poketto-client-')
    path = root / 'client.json'
    owner = path.stat()
    assert not path.is_symlink() and owner.st_uid == 0 and stat.S_IMODE(owner.st_mode) == 0o600
    assert not args.evidence.exists(), 'Preserve the previous acceptance result'
    settings = json.loads(path.read_text())
    assert settings['endpoint'].startswith('http://127.0.0.1:')
    receipt = {'kind': 'native-repository-consumer', 'source': 'real-auth-PG-HTTP-MCP-Sandcrab',
               'modelDriven': False, 'result': 'FAIL', 'checks': []}
    try:
        verify(settings, receipt['checks'])
        receipt['result'] = 'PASS'
    finally:
        with args.evidence.open('x', encoding='utf-8') as output:
            json.dump(receipt, output, indent=2)
            output.write('\n')
        print(json.dumps(receipt), flush=True)


if __name__ == '__main__':
    main()
