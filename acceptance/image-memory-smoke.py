"""Exercise image admission on disposable Linux services; requires stageAcceptanceRuntime and Docker."""

import base64
import argparse
import hashlib
import http.client
import json
import re
from pathlib import Path
import secrets
import socket
import struct
import subprocess
import threading
import time
import urllib.parse
import uuid
import zlib


ROOT = Path(__file__).resolve().parent.parent
MIB = 1024 * 1024
arguments = argparse.ArgumentParser(description=__doc__)
arguments.add_argument('--memory-mib', type=int, choices=(768, 1024), default=768)
memory_mib = arguments.parse_args().memory_mib
EVIDENCE = ROOT / '.gradle' / ('image-memory-' + uuid.uuid4().hex[:10])
EVIDENCE.mkdir(parents=True)
PROJECT = 'poketto-image-' + uuid.uuid4().hex[:10]
password = secrets.token_urlsafe(32)
env = EVIDENCE / 'acceptance.env'
revision = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
env.write_text(f'POKETTO_ACCEPTANCE_PASSWORD={password}\nPOKETTO_ACCEPTANCE_REVISION={revision}\n', encoding='utf-8')
override = EVIDENCE / 'runtime.json'
override.write_text(json.dumps({'services': {'app': {
    'ports': ['127.0.0.1::8080'],
    'cpus': 2,
    'mem_limit': str(memory_mib) + 'm',
    'command': ['java', '-XX:MaxRAMPercentage=65' if memory_mib == 768 else '-Xmx500m', '-XX:+ExitOnOutOfMemoryError', '-Djava.awt.headless=true',
                '-cp', '/runtime/classes:/runtime/jars/*', 'io.github.core607.poketto.acceptance.AcceptanceApplication',
                '--management.endpoints.web.exposure.include=health,metrics'],
}}}), encoding='utf-8')
COMPOSE = ['docker', 'compose', '-p', PROJECT, '--env-file', str(env), '-f', str(ROOT / 'acceptance/compose.yaml'), '-f', str(override)]
proof = {'project': PROJECT, 'commit': revision, 'checks': [], 'samples': [], 'sourceSha256': {}}
proof['probeSha256'] = hashlib.sha256(Path(__file__).read_bytes()).hexdigest()
for area in ('assets', 'mcp', 'web'):
    for path in sorted((ROOT / 'src/main/java/io/github/core607/poketto' / area).rglob('*.java')):
        proof['sourceSha256'][str(path.relative_to(ROOT))] = hashlib.sha256(path.read_bytes()).hexdigest()
cookie, csrf, port = None, None, None
held = []
stop = threading.Event()
monitor_failure = []


def run(args):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, encoding='utf-8', timeout=90)
    if result.returncode:
        raise RuntimeError('command failed: ' + args[0] + '\n' + result.stderr[-2000:])
    return result.stdout


def call(method, path, body=None, ctype=None, extra=None, browser=True, slow=False):
    headers = dict(extra or {})
    if browser and cookie:
        headers['Cookie'] = cookie
    if browser and csrf and method not in ('GET', 'HEAD'):
        headers[csrf['headerName']] = csrf['token']
    if ctype:
        headers['Content-Type'] = ctype
    connection = http.client.HTTPConnection('127.0.0.1', port, timeout=20)
    if slow:
        connection.connect()
        connection.sock.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
    try:
        connection.request(method, path, body, headers)
        response = connection.getresponse()
        if slow:
            held.append((connection, response))
            return response.status, response
        return response.status, response.read(), {name.lower(): value for name, value in response.getheaders()}
    finally:
        if not slow:
            connection.close()


def expect(method, path, expected=200, body=None, ctype=None, extra=None, browser=True):
    status, value, headers = call(method, path, body, ctype, extra, browser)
    if stop.is_set():
        raise RuntimeError('resource probe stopped: ' + '; '.join(monitor_failure))
    assert status == expected, (method, path.split('?', 1)[0], status, expected)
    return value, headers


def json_call(method, path, value=None, expected=200):
    payload = None if value is None else json.dumps(value, ensure_ascii=False).encode()
    result, _ = expect(method, path, expected, payload, 'application/json' if payload else None)
    return json.loads(result)


def metric(name, tags=''):
    data = json_call('GET', '/actuator/metrics/' + name + tags)
    return sum(item['value'] for item in data['measurements'])


def await_reserved(expected, timeout=10):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if stop.is_set():
            raise RuntimeError('resource stop threshold reached')
        if metric('poketto.images.admission.reserved.bytes') == expected:
            return
        time.sleep(.1)
    raise TimeoutError('image reservation did not reach ' + str(expected))


def close_held():
    while held:
        connection, response = held.pop()
        if connection.sock:
            try:
                connection.sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
        response.close()
        connection.close()


def monitor():
    try:
        next_container_sample = 0
        while not stop.wait(.2):
            used = metric('jvm.memory.used', '?tag=area:heap')
            reserved = metric('poketto.images.admission.reserved.bytes')
            proof['samples'].append({'at': round(time.monotonic(), 3), 'heapUsed': used, 'reservedBytes': reserved})
            if used > proof['heapMax'] * .90:
                monitor_failure.append('heap stop threshold: 90 percent of actual maximum')
                stop.set()
                close_held()
                return
            if time.monotonic() >= next_container_sample:
                current, peak = map(int, run(['docker', 'exec', app_id, 'cat', '/sys/fs/cgroup/memory.current', '/sys/fs/cgroup/memory.peak']).split())
                proof.setdefault('containerMemory', []).append({'current': current, 'peak': peak})
                next_container_sample = time.monotonic() + 1
                if current > memory_mib * MIB * .90:
                    monitor_failure.append('container memory stop threshold: 90 percent of its limit')
                    proof['stopResources'] = resource_details()
                    stop.set()
                    close_held()
                    return
    except Exception as error:
        monitor_failure.append(type(error).__name__)
        stop.set()


def resource_details():
    return {
        'cgroupStatEventsPressure': run(['docker', 'exec', app_id, 'cat', '/sys/fs/cgroup/memory.stat', '/sys/fs/cgroup/memory.events', '/sys/fs/cgroup/memory.pressure']),
        'processStatus': run(['docker', 'exec', app_id, 'cat', '/proc/1/status']),
        'heapUsed': metric('jvm.memory.used', '?tag=area:heap'),
        'heapCommitted': metric('jvm.memory.committed', '?tag=area:heap'),
        'heapMax': proof['heapMax'],
    }


def chunk(kind, value):
    return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', zlib.crc32(kind + value))


png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\x22\x55\x77')) + chunk(b'IEND', b'')
png = png[:-12] + chunk(b'tEXt', b'p\0' + b'x' * (16 * MIB - len(png) - 14)) + png[-12:]
digest = hashlib.sha256(png).hexdigest()
assert len(png) == 16 * MIB
started, monitoring = False, None
try:
    run(COMPOSE + ['up', '-d', 'db', 'app'])
    started = True
    binding = run(COMPOSE + ['port', 'app', '8080']).strip()
    assert binding.startswith('127.0.0.1:')
    port = int(binding.rsplit(':', 1)[1])
    deadline = time.monotonic() + 60
    while 'Synthetic acceptance services are ready' not in run(COMPOSE + ['logs', '--no-color', 'app']):
        if time.monotonic() > deadline:
            raise TimeoutError('synthetic service initialization')
        time.sleep(.25)
    value, headers = expect('GET', '/api/auth/csrf')
    csrf = json.loads(value)
    cookie = headers['set-cookie'].split(';', 1)[0]
    _, headers = expect('POST', '/api/auth/login', 204, urllib.parse.urlencode({'username': 'owner', 'password': password}).encode(), 'application/x-www-form-urlencoded')
    cookie = headers.get('set-cookie', cookie).split(';', 1)[0]
    csrf = json_call('GET', '/api/auth/csrf')
    app_id = run(COMPOSE + ['ps', '-q', 'app']).strip()
    proof['jvmFlags'] = run(['docker', 'exec', app_id, 'jcmd', '1', 'VM.flags']).strip()
    proof['heapMax'] = int(re.search(r'-XX:MaxHeapSize=(\d+)', proof['jvmFlags'])[1])
    monitoring = threading.Thread(target=monitor, daemon=True)
    monitoring.start()
    operation = uuid.uuid4().hex
    boundary = 'fixture-' + uuid.uuid4().hex
    multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="large.png"\r\nContent-Type: image/png\r\n\r\n').encode() + png + f'\r\n--{boundary}--\r\n'.encode()
    value, _ = expect('POST', '/api/admin/assets', 200, multipart, 'multipart/form-data; boundary=' + boundary, {'Idempotency-Key': operation})
    reference = json.loads(value)['reference']
    assert reference['revision'] == digest
    managed = 'managed:' + reference['assetId'] + ':' + digest
    body = '# Memory fixture\n![exact original](' + managed + ')'
    preview = json_call('POST', '/api/admin/repository/preview', {'path': 'private/memory.md', 'body': body})
    private_url = preview['images'][managed]
    absent = json_call('GET', '/api/admin/repository/file?path=memory.md')
    json_call('POST', '/api/admin/repository/patch', {'baseCommit': absent['commit'], 'changes': [{'path': 'memory.md', 'expectedAbsence': True, 'content': body}]})
    public = json_call('GET', '/api/public/document?route=/memory')
    public_url = public['images'][managed]
    for image_url in (public_url, private_url):
        value, _ = expect('GET', image_url)
        assert hashlib.sha256(value).hexdigest() == digest
    expect('GET', private_url, 401, browser=False)
    proof['checks'].append('public/private exact 16 MiB hashes and private authentication')
    owner = json_call('GET', '/api/admin/members')['items'][0]['accountId']
    key = json_call('POST', '/api/admin/keys', {'accountId': owner, 'capabilities': ['READ_PRIVATE', 'WRITE_PRIVATE']}, 201)['token']
    mcp_headers = {'Authorization': 'Bearer ' + key, 'Accept': 'application/json, text/event-stream'}
    initialize = {'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {'protocolVersion': '2025-11-25', 'capabilities': {}, 'clientInfo': {'name': 'image-memory-probe', 'version': '1'}}}
    _, headers = expect('POST', '/mcp', 200, json.dumps(initialize).encode(), 'application/json', mcp_headers, False)
    mcp_headers['Mcp-Session-Id'] = headers['mcp-session-id']
    mcp_headers['MCP-Protocol-Version'] = '2025-11-25'
    expect('POST', '/mcp', 202, b'{"jsonrpc":"2.0","method":"notifications/initialized"}', 'application/json', mcp_headers, False)
    get_image = {'jsonrpc': '2.0', 'id': 2, 'method': 'tools/call', 'params': {'name': 'get_asset', 'arguments': {'source': {'kind': 'managed', **reference}}}}
    get_payload = json.dumps(get_image).encode()
    for i in range(2):
        status, _ = call('GET', public_url if i % 2 else private_url, slow=True)
        assert status == 200
    await_reserved(256 * MIB)
    expect('GET', public_url, 429)
    expect('POST', '/api/admin/assets', 429, multipart, 'multipart/form-data; boundary=' + boundary, {'Idempotency-Key': operation})
    expect('POST', '/mcp', 429, get_payload, 'application/json', mcp_headers, False)
    assert json_call('GET', '/api/public/document?route=/memory')['images'] == {}
    assert json_call('POST', '/api/admin/repository/preview', {'path': 'private/memory.md', 'body': body})['images'] == {}
    inventory = json_call('GET', '/api/admin/assets/repository')
    assert any(item['code'] == 'IMAGE_CAPACITY_UNAVAILABLE' for item in inventory['diagnostics'])
    expect('POST', '/mcp', 202, b'{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}', 'application/json', mcp_headers, False)
    proof['checks'].append('two slow public/private HTTP responses fill one shared budget; excess HTTP/upload/MCP reject; text/preview/inventory and cancellation remain available')
    close_held()
    await_reserved(0)
    status, response = call('POST', '/mcp', get_payload, 'application/json', mcp_headers, False, slow=True)
    assert status == 200
    await_reserved(256 * MIB)
    expect('GET', public_url, 429)
    expect('POST', '/mcp', 202, b'{"jsonrpc":"2.0","method":"notifications/cancelled","params":{"requestId":2}}', 'application/json', mcp_headers, False)
    assert metric('poketto.images.admission.reserved.bytes') == 256 * MIB
    close_held()
    await_reserved(0)
    proof['checks'].append('slow MCP SSE output holds shared budget through cancel notification; socket disconnect releases it')
    get_image['id'] = '图' * 128
    value, _ = expect('POST', '/mcp', 200, json.dumps(get_image).encode(), 'application/json', mcp_headers, False)
    result = json.loads(next(line[5:] for line in value.decode().splitlines() if line.startswith('data:')))['result']
    image = next(item for item in result['content'] if item['type'] == 'image')
    assert hashlib.sha256(base64.b64decode(image['data'])).hexdigest() == digest
    put_image = {'jsonrpc': '2.0', 'id': 4, 'method': 'tools/call', 'params': {'name': 'put_asset', 'arguments': {'operationKey': operation, 'base64': base64.b64encode(png).decode()}}}
    value, _ = expect('POST', '/mcp', 200, json.dumps(put_image).encode(), 'application/json', mcp_headers, False)
    result = json.loads(next(line[5:] for line in value.decode().splitlines() if line.startswith('data:')))['result']
    assert result.get('isError') is False, 'maximum MCP upload returned a tool error'
    metadata = json.loads(next(item['text'] for item in result['content'] if item['type'] == 'text'))
    assert metadata['assetId'] == reference['assetId'] and metadata['revision'] == digest
    assert json_call('GET', '/api/admin/assets')['total'] == 1
    await_reserved(0)
    proof['checks'].append('maximum MCP image encoding has exact hash; maximum MCP upload reuses HTTP immutable upload idempotently')
    run(['docker', 'exec', app_id, 'jcmd', '1', 'GC.run'])
    proof['heapAfterGc'] = metric('jvm.memory.used', '?tag=area:heap')
    proof['processStatus'] = run(['docker', 'exec', app_id, 'cat', '/proc/1/status'])
    proof['finalResources'] = resource_details()
    assert not monitor_failure, monitor_failure
    assert not stop.is_set()
    assert json.loads(expect('GET', '/actuator/health')[0])['status'] == 'UP'
    proof['result'] = 'PASS'
except BaseException as error:
    proof['result'] = 'FAIL'
    proof['error'] = type(error).__name__ + ': ' + str(error)
    if started:
        (EVIDENCE / 'app.log').write_text(run(COMPOSE + ['logs', '--no-color', 'app']), encoding='utf-8')
        (EVIDENCE / 'threads.txt').write_text(run(['docker', 'exec', app_id, 'jcmd', '1', 'Thread.print']), encoding='utf-8')
    raise
finally:
    stop.set()
    close_held()
    if monitoring:
        monitoring.join(5)
    proof['monitorFailure'] = monitor_failure
    proof['peakSampledHeap'] = max((sample['heapUsed'] for sample in proof['samples']), default=0)
    if started:
        ids = run(COMPOSE + ['ps', '-aq']).split()
        details = json.loads(run(['docker', 'inspect', *ids]))
        assert len(details) == 2
        assert all(item['Config']['Labels']['com.docker.compose.project'] == PROJECT for item in details)
        proof['containers'] = [{'id': item['Id'], 'oomKilled': item['State']['OOMKilled'], 'restarts': item['RestartCount'], 'limit': item['HostConfig']['Memory'], 'nanoCpus': item['HostConfig']['NanoCpus']} for item in details]
        run(COMPOSE + ['down', '--volumes'])
        assert not run(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project=' + PROJECT]).strip()
        assert not run(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project=' + PROJECT]).strip()
        proof['cleanup'] = 'PASS'
    (EVIDENCE / 'result.json').write_text(json.dumps(proof, indent=2), encoding='utf-8')
    print(json.dumps({'result': proof.get('result'), 'checks': proof['checks'], 'peakSampledHeap': proof['peakSampledHeap'], 'cleanup': proof.get('cleanup'), 'evidence': str(EVIDENCE / 'result.json')}))
