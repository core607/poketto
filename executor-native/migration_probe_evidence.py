"""Independent file and syscall observations for the real native migration fixture."""
import ast
import hashlib
import json
import re
import stat
from pathlib import Path

STRING = re.compile(r'"(?:\\.|[^"\\])*"')
REQUEST = re.compile(r'^(GET|POST|PUT|DELETE|PATCH) (/v1/[^ ]*) HTTP/1\.1\r\n')


def operator_result(stdout, stderr, code):
    for path in (stdout, stderr):
        assert path.stat().st_size <= 1024 * 1024, 'Operator output exceeded its observation bound'
    result, diagnostic = (stdout, stderr) if code == 0 else (stderr, stdout)
    assert not diagnostic.read_bytes().strip(), 'Unexpected output outside the controller result stream'
    # The production CLI owns one JSON document, not JSON-looking lines among diagnostics.
    return json.loads(result.read_text())


def requests(trace):
    assert trace.stat().st_size <= 32 * 1024 * 1024, 'Migration syscall trace exceeded its bound'
    found = []
    for line in trace.read_text(errors='strict').splitlines():
        if not re.search(r'\b(?:write|writev|sendto|sendmsg)\(', line):
            continue
        for literal in STRING.findall(line):
            value = ast.literal_eval(literal)
            if value.startswith(('GET ', 'POST ', 'PUT ', 'DELETE ', 'PATCH ')):
                # SourceGuard's real Docker CLI also uses HTTP, under versioned /v1.N routes.
                # Observe only the native service's fixed /v1/ protocol namespace.
                if not value.split(' ', 2)[1].startswith('/v1/'):
                    continue
                matched = REQUEST.match(value)
                assert matched is not None, 'Unrecognized or incomplete HTTP request in syscall observation'
                found.append(list(matched.groups()))
    return found


def require_prepare(trace):
    observed = requests(trace)
    assert observed == [['GET', '/v1/info']], 'Preparation did not observe exactly one read-only native info request'
    return observed


def require_execute(trace, plan):
    observed = requests(trace)
    assert observed, 'Native import produced no observable HTTP requests'
    expected = [['POST', '/v1/volumes']]
    allowed = {('GET', '/v1/info'), ('GET', '/v1/volumes/' + plan['volume']['id']),
               ('POST', '/v1/volumes'), ('POST', '/v1/sessions'), ('POST', '/v1/uploads')}
    for root in ('work', 'home'):
        part = plan[root]
        expected += [['POST', '/v1/sessions'], ['POST', '/v1/uploads'],
                     ['PUT', '/v1/uploads/' + part['upload']['id'] + '/content'],
                     ['POST', '/v1/sessions/' + part['session']['id'] + '/close']]
        allowed.update({('GET', '/v1/sessions/' + part['session']['id']),
                        ('POST', '/v1/sessions/' + part['session']['id'] + '/close'),
                        ('GET', '/v1/uploads/' + part['upload']['id']),
                        ('PUT', '/v1/uploads/' + part['upload']['id'] + '/content'),
                        ('POST', '/v1/uploads/' + part['upload']['id'] + '/forget')})
    for request in expected:
        assert request in observed, 'Native execution omitted an expected observed operation: ' + str(request)
    assert observed.count(['POST', '/v1/volumes']) == 1
    assert observed.count(['POST', '/v1/sessions']) == 2
    assert observed.count(['POST', '/v1/uploads']) == 2
    assert sum(method == 'PUT' for method, path in observed) == 2, 'The import resent or omitted archive content'
    assert all(tuple(request) in allowed for request in observed), \
        'The data-only importer attempted an operation outside its exact storage plan'
    return observed


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
