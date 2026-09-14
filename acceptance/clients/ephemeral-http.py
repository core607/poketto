#!/usr/bin/env python3
"""Exercise timeout and explicit disposal through the real non-retained MCP entrance."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import stat


spec = importlib.util.spec_from_file_location('retained_http', Path(__file__).with_name('retained-http.py'))
http = importlib.util.module_from_spec(spec)
spec.loader.exec_module(http)


class ConnectedMcp(http.Mcp):
    def __init__(self, endpoint, token):
        super().__init__(endpoint, token)
        self.session = self.initialize()

    def rpc(self, method, params):
        code, _, body = self.send(self.session, method, params)
        assert code == 200, f'MCP request returned HTTP {code}'
        response = self.envelope(body)
        assert 'error' not in response, 'MCP returned a protocol error'
        return response['result']

    def close(self):
        assert self.send(self.session, '', verb='DELETE')[0] in (200, 204)


def verify(browser, client, other):
    tools = client.rpc('tools/list', {})['tools']
    discard = next(tool for tool in tools if tool['name'] == 'repo_discard')
    assert discard['inputSchema']['required'] == ['expectedCopyId']
    first = client.call('repo_exec', {'expectedCopyId': 'new', 'command':
            "set -eu; printf before > private/draft.md; "
            "python3 -c \"from pathlib import Path; Path('private/draft.bin').write_bytes(bytes([0,255]))\""})
    assert first['exitCode'] == 0 and 'retention' not in first
    copy = first['copyId']
    timeout = client.call('repo_exec', {'expectedCopyId': copy, 'timeoutSeconds': 5, 'command':
            'printf partial >> private/draft.md; (sleep 40; touch private/late.md) & wait'})
    assert timeout['timedOut'] and timeout['terminationReason'] == 'TIMEOUT'
    assert timeout['exitCode'] != 0 and timeout['copyId'] == copy
    checked = client.call('repo_exec', {'expectedCopyId': copy, 'command':
            "set -eu; test \"$(cat private/draft.md)\" = beforepartial; test ! -e private/late.md; "
            "python3 -c \"from pathlib import Path; assert Path('private/draft.bin').read_bytes() == bytes([0,255])\""})
    assert checked['exitCode'] == 0 and checked['commit'] == first['commit']
    http.passed('ephemeral-http-timeout-preserves-text-binary-partial-work-and-baseline')
    refused = client.call('repo_exec', {'expectedCopyId': 'new', 'command': 'touch forbidden'}, error=True)
    assert refused['reason'] == 'DIFFERENT_COPY' and refused['executed'] is False
    assert other.call('repo_discard', {'expectedCopyId': copy})['status'] == 'ABSENT'
    assert client.call('repo_discard', {'expectedCopyId': copy})['status'] == 'DISCARDED'
    assert client.call('repo_discard', {'expectedCopyId': copy})['status'] == 'ABSENT'
    fresh = client.call('repo_exec', {'expectedCopyId': 'new', 'command':
            'set -eu; test ! -e private/draft.md; test ! -e private/draft.bin; test ! -e forbidden'})
    assert fresh['exitCode'] == 0 and fresh['copyId'] != copy and fresh['commit'] == first['commit']
    assert client.call('repo_discard', {'expectedCopyId': fresh['copyId']})['status'] == 'DISCARDED'
    authoritative = browser.file('private/draft.md')
    assert authoritative['expectedAbsence'] and authoritative['commit'] == first['commit']
    http.passed('ephemeral-http-owner-bound-idempotent-discard-allows-new-without-changing-remote-git')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    root = parser.parse_args().fixture.resolve(strict=True)
    assert os.geteuid() == 0 and root.parent == Path('/var/lib') and root.name.startswith('poketto-client-')
    info = (root / 'client.json').stat()
    assert info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600
    data = json.loads((root / 'client.json').read_text())
    assert data['endpoint'].startswith('http://127.0.0.1:')
    clients = []
    try:
        browser = http.Browser(data['endpoint'], data['password'])
        for _ in range(2):
            key = browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])
            clients.append(ConnectedMcp(browser.endpoint, key['token']))
        verify(browser, *clients)
        print(json.dumps({'ephemeralHttp': 'PASS', 'tests': 2,
                          'source': 'real-auth-PG-HTTP-MCP-native-SRT', 'modelDriven': False}), flush=True)
    finally:
        try:
            for client in clients:
                client.close()
        finally:
            (root / 'stop').touch()


if __name__ == '__main__':
    main()
