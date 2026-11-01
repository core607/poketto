"""Content contracts through actual HTTP, guest CLI and immutable MCP artifact bytes."""
import base64
import hashlib
import io
import json
from pathlib import PurePosixPath
import struct
import uuid
import zipfile
import zlib

from mcp_http import Mcp

# Public Markdown is rendered, so content markers must survive escaping.
PUBLIC_MARKER = 'NativeSavedPublicContent'
PRIVATE_MARKER = 'NativeSavedPrivateContent'
LOCAL_MARKER = 'NativeUnsavedContent'
AUTHOR_MARKER = 'NativePrivateAuthor'
PUBLIC_NOTE = ('---\ntitle: Native content proof\nauthor: ' + AUTHOR_MARKER
               + '\n---\n\n# Native content proof\n\n' + PUBLIC_MARKER + '\n\n![proof](native-proof.png)\n')
PRIVATE_NOTE = '# Private content proof\n\n' + PRIVATE_MARKER + '\n'
ARTIFACT_BYTES = bytes(range(256)) * 257


def png():
    def chunk(kind, value):
        return struct.pack('!I', len(value)) + kind + value + struct.pack('!I', zlib.crc32(kind + value))
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('!2I5B', 2, 2, 8, 2, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(b'\x00\xff\x00\x00\x00\xff\x00' * 2)) + chunk(b'IEND', b''))


# These scripts use only the public consumer CLI. The controller independently reads
# authoritative HTTP content and MCP resource bytes instead of trusting their assertions.
GUEST = r'''
import base64, hashlib, json, subprocess
from pathlib import Path
def cli(*arguments, error=None):
    result = subprocess.run(['poketto', *arguments], capture_output=True, text=True, timeout=40)
    assert len(result.stdout) <= 65536, 'CLI response exceeded its bound'
    value = json.loads(result.stdout)
    assert result.returncode == (0 if error is None else 1), (arguments, value)
    assert value['ok'] is (error is None), (arguments, value)
    if error is not None:
        assert value['code'] == error, (arguments, value)
    return value
'''

PREPARE = r'''
index = Path('.poketto/assets.json')
before = index.read_bytes() if index.exists() else None
Path('native-index-before.json').write_bytes(before or b'')
Path('private/native-source.png').write_bytes(base64.b64decode(data['png']))
Path('private/native-local.md').write_text(data['local'])
first = cli('media', 'import', 'private/native-source.png', '--path', 'public/native-proof.png',
            '--type', 'image/png', '--key', data['key'])['result']
again = cli('media', 'import', 'private/native-source.png', '--path', 'public/native-proof.png',
            '--type', 'image/png', '--key', data['key'])['result']
assert first == again and first['originalStored'] and not first['indexUpdated'] and not first['saved']
assert first['sha256'] == hashlib.sha256(base64.b64decode(data['png'])).hexdigest()
linked = cli('media', 'link', 'public/native-proof.png', '--asset', first['assetId'],
             '--revision', first['sha256'])['result']
assert linked['indexEntry'] == first['indexEntry'] and not linked['indexUpdated'] and not linked['saved']
assert (index.read_bytes() if index.exists() else None) == before
catalog = json.loads(before) if before is not None else {'version': 1, 'files': {}}
catalog['files']['public/native-proof.png'] = first['indexEntry']
private = cli('media', 'import', 'private/native-local.md', '--path', 'private/native-only.bin',
              '--key', data['privateKey'])['result']
catalog['files']['private/native-only.bin'] = private['indexEntry']
index.parent.mkdir(exist_ok=True)
index.write_text(json.dumps(catalog))
listing = cli('media', 'list', '--prefix', 'public/native-proof.png')['result']
assert [entry['path'] for entry in listing['items']] == ['public/native-proof.png']
assert listing['nextOffset'] is None
fetched = cli('media', 'fetch', 'public/native-proof.png', '--output', 'private/native-fetched.png')['result']
assert fetched['sha256'] == first['sha256']
assert Path('private/native-fetched.png').read_bytes() == base64.b64decode(data['png'])
Path('private/native-fetched.png').write_bytes(b'keep local bytes')
cli('media', 'fetch', 'public/native-proof.png', '--output', 'private/native-fetched.png', error='LOCAL_FILE_EXISTS')
assert Path('private/native-fetched.png').read_bytes() == b'keep local bytes'
Path('public/native-contract.md').write_text(data['publicNote'])
Path('private/native-contract.md').write_text(data['privateNote'])
saved = cli('save', '.poketto/assets.json', 'public/native-contract.md', 'private/native-contract.md')['result']
Path('public/native-contract.md').write_text(data['local'])
Path('private/native-contract.md').write_text(data['local'])
exported = cli('export', 'public/native-contract.md', 'private/native-contract.md', '--output', 'native-private.zip')['result']
assert exported['scope'] == 'private' and not exported['saved']
Path('native-blocked.zip').write_bytes(b'keep local ZIP')
cli('export', 'public/native-contract.md', '--output', 'native-blocked.zip', error='LOCAL_FILE_CHANGED')
assert Path('native-blocked.zip').read_bytes() == b'keep local ZIP'
archive = cli('artifact', 'create', 'native-private.zip', '--type', 'application/zip')['artifact']
Path('native-artifact.bin').write_bytes(bytes(range(256)) * 257)
immutable = cli('artifact', 'create', 'native-artifact.bin')['artifact']
Path('native-artifact.bin').write_bytes(b'changed after capture')
print(json.dumps({'commit': saved['commit'], 'media': first, 'privateMedia': private,
                  'indexWasPresent': before is not None, 'archive': archive, 'export': exported, 'immutable': immutable}))
'''

PUBLIC = r'''
assert subprocess.check_output(['git', 'rev-list', '--all', '--count'], text=True).strip() == '1'
assert subprocess.check_output(['git', 'log', '--all', '--format=%ae'], text=True).strip() == 'poketto@invalid'
assert not Path('private').exists() and not Path('public').exists()
history = subprocess.check_output(['git', 'cat-file', '--batch-all-objects', '--batch'])
for forbidden in data['forbidden']:
    assert forbidden.encode() not in history
    assert forbidden.replace('_', r'\_').encode() not in history
assert data['marker'] in Path('native-contract/index.md').read_text()
catalog = json.loads(Path('.poketto/assets.json').read_text())
assert data['privateAsset'] not in json.dumps(catalog)
assert all(not path.startswith(('private/', 'public/')) for path in catalog['files'])
listing = cli('media', 'list')['result']
aliases = [item['path'] for item in listing['items'] if item['path'].endswith('-native-proof.png')]
assert len(aliases) == 1 and aliases[0] in catalog['files']
assert not any(item['path'].endswith('native-only.bin') for item in listing['items'])
fetched = cli('media', 'fetch', aliases[0], '--output', 'native-public-image.png')['result']
assert fetched['indexSource'] == 'public-projection'
assert Path('native-public-image.png').read_bytes() == base64.b64decode(data['png'])
cli('save', 'native-contract/index.md', error='ACCESS_DENIED')
exported = cli('export', 'native-contract', '--output', 'native-public.zip')['result']
assert exported['scope'] == 'public'
archive = cli('artifact', 'create', 'native-public.zip', '--type', 'application/zip')['artifact']
image = cli('artifact', 'create', 'native-public-image.png', '--type', 'image/png')['artifact']
print(json.dumps({'archive': archive, 'export': exported, 'image': image,
                  'projectionCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()}))
'''

RESTORE = r'''
index = Path('.poketto/assets.json')
Path('private/native-contract.md').unlink()
if data['indexWasPresent']:
    index.write_bytes(Path('native-index-before.json').read_bytes())
    cli('save', '.poketto/assets.json', '--delete', 'private/native-contract.md')
else:
    index.unlink()
    cli('save', '--delete', '.poketto/assets.json', '--delete', 'private/native-contract.md')
'''


def script(source, data):
    program = GUEST + '\ndata = json.loads(' + repr(json.dumps(data)) + ')\n' + source
    compile(program, '<native-content-guest>', 'exec')
    command = "python3 -I - <<'NATIVE_CONTENT_CHECK'\n" + program + '\nNATIVE_CONTENT_CHECK\n'
    assert len(command) <= 16384, 'Guest source exceeds the repository command bound'
    return command


def execute(client, copy, command, owned):
    result = client.call('repo_exec', {'expectedCopyId': copy, 'command': command, 'timeoutSeconds': 60})
    actual = str(uuid.UUID(result['copyId']))
    if copy == 'new':
        owned.append((client, actual))
    else:
        assert actual == copy
    assert result['exitCode'] == 0, 'Guest content check failed: ' + result['stderr'][-2000:]
    return result


def artifact_bytes(client, metadata):
    """Bound every page and verify identity and digest before using returned resource bytes."""
    # CLI ArtifactMetadata uses bytes; get_artifact's page uses size.
    size = metadata['bytes']
    assert 0 < size <= 2 * 1024 * 1024 and not metadata['truncated']
    received = bytearray()
    while len(received) < size:
        limit = min(65536, max(4, size - len(received)))
        result = client.rpc('tools/call', {'name': 'get_artifact', 'arguments': {
            'artifactId': metadata['artifactId'], 'offset': len(received), 'limit': limit, 'format': 'bytes'}})
        assert not result.get('isError'), 'Artifact delivery failed'
        content = result['content']
        page = json.loads(next(item['text'] for item in content if item['type'] == 'text'))
        for key in ('artifactId', 'sha256', 'mediaType', 'name', 'truncated'):
            assert page[key] == metadata[key], 'Artifact identity changed: ' + key
        assert page['size'] == size and page['offset'] == len(received)
        resources = [item['resource'] for item in content if item['type'] == 'resource']
        assert len(resources) == 1
        resource = resources[0]
        assert resource['uri'] == 'poketto-artifact:///' + metadata['artifactId'] + '?offset=' + str(len(received))
        assert resource['mimeType'] == 'application/octet-stream'
        part = base64.b64decode(resource['blob'], validate=True)
        assert 0 < len(part) <= min(limit, size - len(received))
        received.extend(part)
        assert page['nextOffset'] == (None if len(received) == size else len(received))
    assert hashlib.sha256(received).hexdigest() == metadata['sha256'], 'Artifact digest mismatch'
    return bytes(received)


def verify_archive(content, public):
    with zipfile.ZipFile(io.BytesIO(content)) as archive:
        members = archive.infolist()
        assert len(members) == (2 if public else 3) and len({item.filename for item in members}) == len(members)
        assert sum(item.file_size for item in members) <= 1024 * 1024
        for item in members:
            path = PurePosixPath(item.filename)
            assert not path.is_absolute() and '\\' not in item.filename
            assert all(part not in ('.', '..') and not part.startswith('.') and part.lower() != 'agents.md'
                       for part in path.parts)
        files = {item.filename: archive.read(item) for item in members}
    images = [value for path, value in files.items() if path.endswith('.png')]
    documents = [value for path, value in files.items() if path.endswith('.md')]
    assert images == [png()] and len(documents) == (1 if public else 2)
    joined = b'\n'.join(documents)
    assert PUBLIC_MARKER.encode() in joined and LOCAL_MARKER.encode() not in joined
    assert (PRIVATE_MARKER.encode() in joined) is (not public)
    if public:
        assert AUTHOR_MARKER.encode() not in joined


def verify(browser, settings, native, checks):
    before = {item['id'] for item in native(settings, 'volume', 'list')['volumes']}
    keys, owned = [], []
    try:
        key = browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'PUBLISH', 'EXECUTE_REPOSITORY'])
        keys.append(key)
        writer = Mcp(browser.endpoint, key['token'])
        key = browser.key(['EXECUTE_REPOSITORY'])
        keys.append(key)
        reader = Mcp(browser.endpoint, key['token'])
        prepared = execute(writer, 'new', script(PREPARE, {
            'key': str(uuid.uuid4()), 'privateKey': str(uuid.uuid4()), 'png': base64.b64encode(png()).decode(),
            'local': LOCAL_MARKER,
            'publicNote': PUBLIC_NOTE, 'privateNote': PRIVATE_NOTE}), owned)
        full = prepared['copyId']
        receipt = json.loads(prepared['stdout'])
        for path, source in [('public/native-contract.md', PUBLIC_NOTE), ('private/native-contract.md', PRIVATE_NOTE)]:
            observed = browser.file(path)
            assert observed['source'] == source and observed['commit'] == receipt['commit']
        assert browser.file('private/native-local.md')['expectedAbsence']
        checks.append('native-cli-media-proposals-idempotency-fetch-and-selected-save')
        private_zip = artifact_bytes(writer, receipt['archive'])
        assert len(private_zip) == receipt['export']['bytes']
        assert hashlib.sha256(private_zip).hexdigest() == receipt['export']['sha256']
        verify_archive(private_zip, public=False)
        assert artifact_bytes(writer, receipt['immutable']) == ARTIFACT_BYTES
        denied = reader.call('get_artifact', {'artifactId': receipt['immutable']['artifactId']}, error=True)
        assert denied['code'] == 'DENIED'
        execute(writer, full, 'poketto artifact remove ' + receipt['immutable']['artifactId'], owned)
        removed = writer.call('get_artifact', {'artifactId': receipt['immutable']['artifactId']}, error=True)
        assert removed['code'] == 'ARTIFACT_UNAVAILABLE'
        checks.append('native-private-export-uses-saved-content-and-artifacts-are-immutable-and-scope-bound')
        published = browser.api('GET', '/api/public/document?route=/native-contract')
        assert PUBLIC_MARKER in published['body'] and AUTHOR_MARKER not in json.dumps(published)
        public_result = execute(reader, 'new', script(PUBLIC, {
            'png': base64.b64encode(png()).decode(), 'marker': PUBLIC_MARKER,
            'privateAsset': receipt['privateMedia']['assetId'],
            'forbidden': [PRIVATE_MARKER, AUTHOR_MARKER, LOCAL_MARKER, 'PRIVATE_ACCEPTANCE_SENTINEL',
                          'EXCLUDED_ACCEPTANCE_SENTINEL', 'PRIVATE_AUTHOR_ACCEPTANCE_SENTINEL']}), owned)
        public = public_result['copyId']
        public_receipt = json.loads(public_result['stdout'])
        assert public_result['commit'] == public_receipt['projectionCommit'] != receipt['commit']
        public_zip = artifact_bytes(reader, public_receipt['archive'])
        assert len(public_zip) == public_receipt['export']['bytes']
        assert hashlib.sha256(public_zip).hexdigest() == public_receipt['export']['sha256']
        verify_archive(public_zip, public=True)
        image = reader.rpc('tools/call', {'name': 'get_artifact', 'arguments': {
            'artifactId': public_receipt['image']['artifactId']}})
        assert not image.get('isError')
        images = [item for item in image['content'] if item['type'] == 'image']
        assert len(images) == 1 and images[0]['mimeType'] == 'image/png'
        assert base64.b64decode(images[0]['data'], validate=True) == png()
        enlarged = execute(writer, public, 'test ! -d private && test ! -d public && poketto status --remote', owned)
        status = json.loads(enlarged['stdout'])['result']
        assert enlarged['commit'] == public_result['commit'] and status['scope'] == 'public'
        assert status['remote']['state'] == 'PUBLIC_PROJECTION'
        checks.append('native-public-copy-has-parentless-sanitized-history-approved-media-and-portable-export')
        execute(writer, full, 'rm public/native-contract.md && poketto save --delete public/native-contract.md', owned)
        browser.api('GET', '/api/public/document?route=/native-contract', expected=404)
        refused = reader.call('repo_exec', {'expectedCopyId': public, 'command': 'printf forbidden'}, error=True)
        assert refused['code'] == 'EXECUTION_REFUSED' and refused['reason'] == 'PUBLICATION_CHANGED'
        assert refused['executed'] is False and refused['copyId'] == public
        assert native(settings, 'volume', 'get', public)['state'] == 'ready'
        unavailable = reader.call('get_artifact', {'artifactId': public_receipt['archive']['artifactId']}, error=True)
        assert unavailable['code'] == 'UNAVAILABLE'
        current = execute(reader, 'new', script(r'''
assert not Path('native-contract').exists() and not Path('private').exists()
assert not any(path.endswith('-native-proof.png') for path in json.loads(Path('.poketto/assets.json').read_text())['files'])
print(json.dumps({'projectionCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()}))
''', {}), owned)
        assert current['copyId'] != public
        assert current['commit'] == json.loads(current['stdout'])['projectionCommit'] != public_result['commit']
        assert native(settings, 'volume', 'get', public)['state'] == 'ready'
        checks.append('native-publication-withdrawal-refuses-old-copy-and-artifact-without-deleting-work')
        execute(writer, full, script(RESTORE, {'indexWasPresent': receipt['indexWasPresent']}), owned)
    finally:
        failures = []
        for client, copy in reversed(owned):
            try:
                assert client.call('repo_discard', {'expectedCopyId': copy})['status'] in ('DISCARDED', 'ABSENT')
            except Exception as failure:
                failures.append(type(failure).__name__ + ' disposing ' + copy)
        for key in reversed(keys):
            try:
                browser.api('DELETE', browser.admin + '/keys/' + key['id'], expected=204)
            except Exception as failure:
                failures.append(type(failure).__name__ + ' revoking fixture key')
        assert not failures, 'Content fixture cleanup failed: ' + ', '.join(failures)
        assert {item['id'] for item in native(settings, 'volume', 'list')['volumes']} == before
    checks.append('native-content-fixture-releases-its-grants-and-volumes')
