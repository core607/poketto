"""Complete-corpus acceptance on a disposable native service; no model credentials or calls."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import signal
import subprocess
import tempfile


LAB = Path(__file__).resolve().parents[1]
TRANSFER_BYTES = 256 * 1024**2


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def corpus(data):
    pins = json.loads((LAB / 'dataset.json').read_text())
    audit = json.loads((data / 'audit.json').read_text())
    manifest = json.loads((data / 'corpus-manifest.json').read_text())
    assert audit['pins'] == pins and audit['databaseSha256'] == pins['databaseSha256']
    assert audit['integrity'] == 'ok' and audit['dimensions'] == pins['dimensions']
    assert audit['documents'] == manifest['documents'] == 57600, 'The complete audited corpus is required'
    assert not audit['standardBeir']['extraIds'] and not audit['standardBeir']['changedTextIds']
    assert sha(data / 'fiqa.db') == pins['databaseSha256'], 'Prepared source database changed'
    paths = manifest['documentPaths']
    assert len(paths) == len(set(paths.values())) == manifest['documents']
    for identifier, relative in paths.items():
        path = PurePosixPath(relative)
        assert re.fullmatch(r'[A-Za-z0-9_-]+', identifier)
        assert not path.is_absolute() and '..' not in path.parts and '\\' not in relative
        assert path.parts[0] == 'corpus' and path.name == identifier + '.md'
    bundle = data / 'corpus.bundle'
    assert bundle.is_file() and not bundle.is_symlink()
    assert 0 < bundle.stat().st_size == manifest['bundleBytes'] <= TRANSFER_BYTES
    assert sha(bundle) == manifest['bundleSha256'], 'Prepared corpus bundle changed'
    assert re.fullmatch('[0-9a-f]{40}', manifest['commit'])
    return manifest


def stage(source, destination):
    destination.mkdir(mode=0o700)
    for name in ('corpus.bundle', 'corpus-manifest.json'):
        target = destination / name
        shutil.copyfile(source / name, target)
        target.chmod(0o600)
        if sha(target) != sha(source / name):
            raise ValueError('Prepared corpus changed during staging')


def main():
    parser = argparse.ArgumentParser(description='Run the complete corpus against a dedicated Sandcrab service.')
    for name in ('binary', 'socket', 'node', 'data', 'output'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--profile', default='corpus')
    args = parser.parse_args()
    if os.geteuid() == 0:
        parser.error('Use the unprivileged application account authorized on the dedicated socket')
    data, output = args.data.resolve(strict=True), args.output.absolute()
    output.mkdir(mode=0o700)
    record = {'result': 'FAIL', 'cleanup': 'UNCONFIRMED'}
    try:
        manifest = corpus(data)
        client_data = output / 'client-data'
        stage(data, client_data)
        record['corpus'] = {key: value for key, value in manifest.items() if key != 'documentPaths'}
        binary, node = args.binary.resolve(strict=True), args.node.resolve(strict=True)
        record['suppliedBinarySha256'] = sha(binary)
        with tempfile.TemporaryDirectory(prefix='retrieval-host-marker-') as temp:
            sentinel = Path(temp) / 'readable-on-host'
            sentinel.write_text('host file\n')
            sentinel.chmod(0o444)
            sentinel.parent.chmod(0o755)
            environment = {'PATH': os.defpath, 'LANG': 'C.UTF-8', 'HOME': str(client_data),
                           'LAB_DATA': str(client_data), 'LAB_WORKER_SOCKET': str(args.socket),
                           'LAB_WORKER_PROFILE': args.profile, 'LAB_ACCEPTANCE_HOST_SENTINEL': str(sentinel)}
            with (output / 'consumer.stdout').open('xb') as stdout, (output / 'consumer.stderr').open('xb') as stderr:
                process = subprocess.Popen([str(node), '--max-old-space-size=128', 'dist/src/native-acceptance.js'],
                    cwd=LAB, env=environment, stdin=subprocess.DEVNULL, stdout=stdout, stderr=stderr, start_new_session=True)
                try:
                    code = process.wait(timeout=600)
                finally:
                    try:
                        os.killpg(process.pid, signal.SIGKILL)
                    except ProcessLookupError:
                        pass
                    process.wait(timeout=15)
        if code:
            raise RuntimeError('Consumer failed. Inspect its retained stdout and stderr.')
        stdout = output / 'consumer.stdout'
        assert stdout.stat().st_size <= 65536
        results = [json.loads(line) for line in stdout.read_text().splitlines()]
        assert len(results) == 3 and all(row['result'] == 'PASS' for row in results)
        assert results[0]['documents'] == 57600 and results[0]['navigation'] > 32 and results[0]['queries'] == []
        record.update(result='PASS', cleanup='PASS', checks=results)
    except BaseException as failure:
        record['failure'] = str(failure)
        raise
    finally:
        (output / 'summary.json').write_text(json.dumps(record, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
