#!/usr/bin/env python3
"""Real HTTP/MCP image transfer probe against an explicitly selected synthetic acceptance service."""
import argparse
import base64
import hashlib
import http.client
import time
import http.cookiejar
import json
import os
from pathlib import Path
import struct
import ssl
import urllib.error
import urllib.parse
import urllib.request
import uuid
import zlib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--base-url', required=True)
parser.add_argument('--report', required=True, type=Path)
args = parser.parse_args()
base = args.base_url.rstrip('/')
password = os.environ['POKETTO_ACCEPTANCE_PASSWORD']
jar = http.cookiejar.CookieJar()
tls = ssl.create_default_context()
if os.environ.get('POKETTO_ACCEPTANCE_CA'):
    tls.load_verify_locations(os.environ['POKETTO_ACCEPTANCE_CA'])
browser = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), urllib.request.HTTPSHandler(context=tls))
raw = urllib.request.build_opener(urllib.request.HTTPSHandler(context=tls))
csrf = None
sequence = 0
checks = []


def request(method, path, body=None, headers=None, authenticated=False, expected=200):
    headers = dict(headers or {})
    if authenticated and csrf and method not in ('GET', 'HEAD'):
        headers[csrf['headerName']] = csrf['token']
    if isinstance(body, dict):
        body = json.dumps(body).encode()
        headers['Content-Type'] = 'application/json'
    target = path if path.startswith('https://') or path.startswith('http://') else base + path
    try:
        response = (browser if authenticated else raw).open(
            urllib.request.Request(target, data=body, method=method, headers=headers), timeout=45)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        data = response.read()
        if response.status != expected:
            raise AssertionError(f'{method}: expected {expected}, got {response.status}; body={data[:200]!r}')
        return data, response.headers


def jrequest(method, path, body=None, expected=200):
    return json.loads(request(method, path, body, authenticated=True, expected=expected)[0])


csrf = jrequest('GET', '/api/auth/csrf')
request('POST', '/api/auth/login', urllib.parse.urlencode({'username': 'owner', 'password': password}).encode(),
        {'Content-Type': 'application/x-www-form-urlencoded'}, True, 204)
csrf = jrequest('GET', '/api/auth/csrf')
workspace = jrequest('GET', '/api/auth/workspaces')['items'][0]['workspaceId']
admin = '/api/admin/workspaces/' + workspace
owner = jrequest('GET', admin + '/members')['items'][0]['accountId']
key = jrequest('POST', admin + '/keys', {'accountId': owner,
               'capabilities': ['READ_PRIVATE', 'WRITE_PRIVATE']}, 201)
headers = {'Authorization': 'Bearer ' + key['token'], 'Accept': 'application/json, text/event-stream'}


def rpc(method, params):
    global sequence
    sequence += 1
    data, response_headers = request('POST', '/mcp', {'jsonrpc': '2.0', 'id': sequence,
        'method': method, 'params': params}, headers)
    messages = [json.loads(line[5:].lstrip()) for line in data.decode().splitlines() if line.startswith('data:')]
    response = messages[-1] if messages else json.loads(data)
    if 'error' in response:
        raise AssertionError(response['error'])
    return response['result'], response_headers


_, initialized = rpc('initialize', {'protocolVersion': '2025-11-25', 'capabilities': {},
                                   'clientInfo': {'name': 'image-transfer-smoke', 'version': '1'}})
headers['Mcp-Session-Id'] = initialized['Mcp-Session-Id']
headers['MCP-Protocol-Version'] = '2025-11-25'
request('POST', '/mcp', {'jsonrpc': '2.0', 'method': 'notifications/initialized'}, headers, expected=202)


def tool(name, arguments, error=None):
    result, _ = rpc('tools/call', {'name': name, 'arguments': arguments})
    content = json.loads(result['content'][0]['text'])
    if error:
        assert result.get('isError') and content['code'] == error, content
    else:
        assert not result.get('isError'), content
    return content, result


def chunk(kind, value):
    return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value))


png = (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0))
       + chunk(b'IDAT', zlib.compress(b'\x00\x22\x55\x77')) + chunk(b'IEND', b''))
operation = uuid.uuid4().hex
prepare = {'mode': 'upload', 'operationKey': operation}
grant, _ = tool('put_asset', prepare)
url = grant['uploadUrl']
request('GET', url, expected=409)
receipt = json.loads(request('PUT', url, png, {'Content-Type': 'application/octet-stream'})[0])
assert receipt['revision'] == hashlib.sha256(png).hexdigest()
assert json.loads(request('GET', url)[0]) == receipt
assert json.loads(request('PUT', url, png, {'Content-Type': 'application/octet-stream'})[0]) == receipt
assert tool('put_asset', prepare)[0] == grant
checks.append('raw PUT, receipt GET, identical retry and grant reuse')
changed = png[:-12] + chunk(b'tEXt', b'note\0different') + png[-12:]
request('PUT', url, changed, {'Content-Type': 'application/octet-stream'}, expected=409)
request('PUT', url, b'<html>not an image</html>', {'Content-Type': 'application/octet-stream'}, expected=422)
request('PUT', url, png, {'Content-Type': 'text/plain'}, expected=415)
checks.append('changed bytes conflict; invalid image and content type are rejected')
source = {'kind': 'managed', 'assetId': receipt['assetId'], 'revision': receipt['revision']}
_, result = tool('get_asset', {'source': source})
assert base64.b64decode(result['content'][1]['data']) == png
checks.append('exact managed original readback via MCP')
tool('put_asset', {'operationKey': uuid.uuid4().hex, 'url': 'https://127.0.0.1/private'}, 'SOURCE_UNAVAILABLE')
checks.append('private URL is refused')
remote_url = 'https://raw.githubusercontent.com/github/explore/main/topics/python/python.png'
original = raw.open(remote_url, timeout=30).read()
remote_key = uuid.uuid4().hex
remote_receipt, _ = tool('put_asset', {'operationKey': remote_key, 'url': remote_url})
assert remote_receipt['revision'] == hashlib.sha256(original).hexdigest()
file_receipt, _ = tool('put_asset', {'operationKey': remote_key,
    'file': {'download_url': remote_url, 'file_id': 'synthetic-file-reference'}})
assert file_receipt == remote_receipt
checks.append('public HTTPS download and file-object input return identical durable receipt')
# An authenticated grant must not hold memory indefinitely when a sender stops mid-body.
slow_grant, _ = tool('put_asset', {'mode': 'upload', 'operationKey': uuid.uuid4().hex})
slow_url = urllib.parse.urlsplit(slow_grant['uploadUrl'])
connection = http.client.HTTPSConnection(slow_url.hostname, slow_url.port or 443, timeout=40, context=tls)
started = time.monotonic()
try:
    connection.putrequest('PUT', slow_url.path)
    connection.putheader('Content-Type', 'application/octet-stream')
    connection.putheader('Content-Length', str(len(png)))
    connection.endheaders()
    connection.send(png[:8])
    # Caddy can defer an early upstream response until the client stops sending.
    # Observe release through a second connection while the original upload is still open.
    time.sleep(32)
    request('GET', slow_grant['uploadUrl'], expected=409)
    tool('put_asset', {'mode': 'upload', 'operationKey': uuid.uuid4().hex})
    recovered = json.loads(request('PUT', slow_grant['uploadUrl'], png,
        {'Content-Type': 'application/octet-stream'})[0])
    assert recovered['revision'] == hashlib.sha256(png).hexdigest()
finally:
    connection.close()
assert time.monotonic() - started < 40
tool('put_asset', {'mode': 'upload', 'operationKey': uuid.uuid4().hex})
checks.append('stalled raw upload releases admission after 30 seconds; subsequent MCP work and same-grant retry succeed')
request('DELETE', admin + '/keys/' + key['id'], authenticated=True, expected=204)
request('GET', url, expected=403)
checks.append('revoking the original key revokes outstanding upload grants')
args.report.parent.mkdir(parents=True, exist_ok=True)
args.report.write_text(json.dumps({'checks': checks, 'imageSha256': receipt['revision'],
    'remoteImageSha256': remote_receipt['revision'], 'client': 'Python HTTP/MCP probe',
    'notVerified': ['ChatGPT automatic fileParams forwarding', 'Claude app file upload']}, indent=2) + '\n')
print(json.dumps({'passed': checks}))
