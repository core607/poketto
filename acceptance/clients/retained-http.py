#!/usr/bin/env python3
"""Exercise retained copies through real browser authentication and independent HTTP MCP sessions."""
import argparse
import http.cookiejar
import json
import os
from pathlib import Path
import re
import stat
import urllib.error
import urllib.parse
import urllib.request


def request(opener, endpoint, method, path, data=None, headers=None):
    operation = urllib.request.Request(endpoint + path, data=data, headers=headers or {}, method=method)
    try:
        response = opener.open(operation, timeout=75)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = response.read(4 * 1024 * 1024 + 1)
        assert len(body) <= 4 * 1024 * 1024, 'HTTP fixture response exceeded its bound'
        return response.status, dict(response.headers.items()), body


def passed(name):
    print(json.dumps({'test': name, 'result': 'PASS', 'source': 'real-auth-PG-HTTP-MCP-native-SRT'}), flush=True)


class Browser:
    def __init__(self, endpoint, password):
        self.endpoint = endpoint
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.csrf = self.api('GET', '/api/auth/csrf')
        headers = {'Origin': endpoint, 'Content-Type': 'application/x-www-form-urlencoded',
                   self.csrf['headerName']: self.csrf['token']}
        code, _, _ = request(self.opener, endpoint, 'POST', '/api/auth/login',
                             urllib.parse.urlencode({'username': 'owner', 'password': password}).encode(), headers)
        assert code == 204, 'Synthetic owner login failed'
        self.csrf = self.api('GET', '/api/auth/csrf')
        spaces = self.api('GET', '/api/auth/workspaces')['items']
        assert len(spaces) == 1, 'This fixture expects one synthetic workspace'
        self.workspace = spaces[0]['workspaceId']
        self.account = self.api('GET', '/api/auth/workspaces/' + self.workspace + '/me')['accountId']
        self.admin = '/api/admin/workspaces/' + self.workspace

    def api(self, method, path, body=None, expected=200):
        headers = {'Origin': self.endpoint, 'Content-Type': 'application/json'}
        if method != 'GET':
            headers[self.csrf['headerName']] = self.csrf['token']
        code, _, response = request(self.opener, self.endpoint, method, path,
                                    None if body is None else json.dumps(body).encode(), headers)
        assert code == expected, f'{method} {path} returned HTTP {code}, expected {expected}'
        return json.loads(response) if response else None

    def key(self, capabilities):
        return self.api('POST', self.admin + '/keys',
                        {'accountId': self.account, 'capabilities': capabilities}, 201)

    def file(self, path):
        return self.api('GET', self.admin + '/repository/file?' + urllib.parse.urlencode({'path': path}))


class Mcp:
    def __init__(self, endpoint, token):
        self.endpoint, self.token = endpoint, token
        self.opener = urllib.request.build_opener()
        self.sequence = 0

    def send(self, session, method, params=None, notification=False, verb='POST'):
        headers = {'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json',
                   'Accept': 'application/json, text/event-stream'}
        if session:
            headers.update({'Mcp-Session-Id': session, 'MCP-Protocol-Version': '2025-11-25'})
        self.sequence += 1
        body = {'jsonrpc': '2.0', 'method': method}
        if not notification:
            body['id'] = self.sequence
        if params is not None:
            body['params'] = params
        return request(self.opener, self.endpoint, verb, '/mcp',
                       json.dumps(body).encode() if verb == 'POST' else None, headers)

    def initialize(self, expected=200):
        code, headers, body = self.send(None, 'initialize', {'protocolVersion': '2025-11-25',
                'capabilities': {}, 'clientInfo': {'name': 'retained-http-acceptance', 'version': '1'}})
        assert code == expected, f'MCP initialization returned HTTP {code}, expected {expected}'
        if expected != 200:
            return None
        session = next(value for name, value in headers.items() if name.lower() == 'mcp-session-id')
        assert self.envelope(body)['result']['serverInfo']['name'] == 'poketto'
        assert self.send(session, 'notifications/initialized', notification=True)[0] == 202
        return session

    @staticmethod
    def envelope(body):
        text = body.decode('utf-8').strip()
        if text.startswith('{'):
            return json.loads(text)
        events = [json.loads(line[5:].strip()) for line in text.splitlines() if line.startswith('data:')]
        return next(item for item in reversed(events) if 'id' in item)

    def rpc(self, method, params):
        session = self.initialize()
        try:
            code, _, body = self.send(session, method, params)
            assert code == 200, f'MCP request returned HTTP {code}'
            envelope = self.envelope(body)
            assert 'error' not in envelope, 'MCP returned a protocol error'
            return envelope['result']
        finally:
            code, _, _ = self.send(session, '', verb='DELETE')
            assert code in (200, 204), f'MCP session deletion returned HTTP {code}'

    def call(self, name, arguments, error=False):
        result = self.rpc('tools/call', {'name': name, 'arguments': arguments})
        body = json.loads(next(item['text'] for item in result['content'] if item['type'] == 'text'))
        codes = [body.get(field, '') for field in ('code', 'reason')]
        safe = '/'.join(value for value in codes if isinstance(value, str) and re.fullmatch('[A-Z_]{1,64}', value))
        assert bool(result.get('isError')) == error, f'{name} returned an unexpected tool status: {safe}'
        return body

    def execute(self, command, prior=None, error=False):
        arguments = {'expectedCopyId': 'new', 'command': command}
        if prior:
            arguments.update({'expectedCopyId': prior['copyId'],
                              'expectedGeneration': prior['retention']['generation'], 'resume': True})
        result = self.call('repo_exec', arguments, error)
        if not error:
            assert result['exitCode'] == 0, 'Native execution returned a nonzero exit code'
            assert result['retention']['generation'] >= 1
        return result

    def discard(self, prior):
        return self.call('repo_discard', {'expectedCopyId': prior['copyId'],
                         'expectedGeneration': prior['retention']['generation']})


def private_recovery(browser, private, other):
    first = private.execute("set -eu; printf 'retained HTTP draft' > private/http-draft.md; "
            "python3 -c \"from pathlib import Path; Path('private/http-draft.bin').write_bytes(bytes([0,255,11]))\"")
    assert other.discard(first)['status'] == 'ABSENT'
    restored = private.execute("set -eu; test \"$(cat private/http-draft.md)\" = 'retained HTTP draft'; "
            "python3 -c \"from pathlib import Path; assert Path('private/http-draft.bin').read_bytes() == bytes([0,255,11])\"", first)
    assert restored['copyId'] == first['copyId'] and restored['commit'] == first['commit']
    assert restored['retention']['generation'] == first['retention']['generation'] + 1
    assert restored['retention']['resumed'] is True
    refused = private.execute('touch private/stale-must-not-exist', first, error=True)
    assert refused['code'] == 'EXECUTION_REFUSED' and refused['reason'] == 'GENERATION_MISMATCH'
    assert refused['executed'] is False
    saved = private.execute('set -eu; test ! -e private/stale-must-not-exist; poketto save private/http-draft.md', restored)
    authoritative = browser.file('private/http-draft.md')
    assert authoritative['source'] == 'retained HTTP draft'
    passed('retained-http-new-transports-preserve-work-fence-stale-generations-and-save')
    assert private.discard(saved)['status'] == 'DISCARDED'
    assert private.discard(saved)['status'] == 'ABSENT'
    assert private.execute('touch forbidden', saved, error=True)['reason'] == 'MISSING_COPY'
    assert browser.file('private/http-draft.md')['commit'] == authoritative['commit']
    passed('retained-http-discard-is-owner-bound-idempotent-and-preserves-remote-git')


def public_recovery(public):
    first = public.execute("set -eu; test ! -e private; printf 'public draft' > scratch.md")
    restored = public.execute("set -eu; test ! -e private; test \"$(cat scratch.md)\" = 'public draft'", first)
    assert restored['copyId'] == first['copyId'] and restored['commit'] == first['commit']
    assert restored['retention']['resumed'] is True
    assert public.discard(restored)['status'] == 'DISCARDED'
    passed('retained-http-public-projection-resumes-without-private-files')


def revoke(browser, survivor):
    issued = browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])
    client = Mcp(browser.endpoint, issued['token'])
    copy = client.execute("printf 'revoked local draft' > private/revoked.md")
    browser.api('DELETE', browser.admin + '/keys/' + issued['id'], expected=204)
    assert client.initialize(expected=401) is None
    assert survivor.execute('touch forbidden', copy, error=True)['reason'] == 'MISSING_COPY'
    passed('retained-http-revoked-credential-and-other-subject-cannot-recover')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fixture', type=Path, required=True)
    args = parser.parse_args()
    root = args.fixture.resolve(strict=True)
    assert os.geteuid() == 0 and root.parent == Path('/var/lib') and root.name.startswith('poketto-client-')
    receipt = root / 'client.json'
    info = receipt.stat()
    assert info.st_uid == 0 and stat.S_IMODE(info.st_mode) == 0o600
    data = json.loads(receipt.read_text())
    endpoint = urllib.parse.urlparse(data['endpoint'])
    assert endpoint.scheme == 'http' and endpoint.hostname == '127.0.0.1'
    try:
        browser = Browser(data['endpoint'], data['password'])
        private = Mcp(browser.endpoint, browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])['token'])
        public = Mcp(browser.endpoint, browser.key(['EXECUTE_REPOSITORY'])['token'])
        assert {tool['name'] for tool in private.rpc('tools/list', {})['tools']} == {
                'repo_exec', 'repo_discard', 'get_artifact', 'get_asset', 'put_asset'}
        passed('retained-http-authenticated-five-tool-catalog')
        private_recovery(browser, private, public)
        public_recovery(public)
        revoke(browser, private)
        print(json.dumps({'retainedHttp': 'PASS', 'tests': 5,
                          'source': 'real-auth-PG-HTTP-MCP-native-SRT', 'modelDriven': False}), flush=True)
    finally:
        (root / 'stop').touch()


if __name__ == '__main__':
    main()
