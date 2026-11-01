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
import sys

LAB = Path(__file__).resolve().parents[1]
REPOSITORY = LAB.parents[1]
POOL_BYTES = 8 * 1024**3
COPY_BYTES = 2 * 1024**3
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


def configuration(probe, tools):
    return f'''[service]
runtime_root = {json.dumps(str(probe.socket.parent))}
socket = {json.dumps(str(probe.socket))}
client_uid = {probe.client.pw_uid}
client_gid = {probe.client.pw_gid}
resource_slice = "{probe.slice}"
supervisor_unit = "{probe.unit}"
unit_prefix = "{probe.name}-"
max_sandboxes = 1
max_records = 32
max_output_bytes = 16777216
max_file_records = 4
max_file_transfers = 1
[profiles.corpus]
exec_user = "nobody"
tools = {json.dumps(str(tools))}
read_only = ["/usr", "/bin", "/lib", "/lib64", "/etc/ld.so.cache"]
memory_bytes = 536870912
temporary_bytes = 33554432
temporary_inodes = 4096
tasks_max = 64
cpu_quota_percent = 100
timeout_millis = 120000
session_ttl_millis = 60000
output_limit_bytes = 1048576
transfer_bytes = {TRANSFER_BYTES}
network = "none"
host_calls = false
[storage]
root = {json.dumps(str(probe.storage))}
pool_bytes = {POOL_BYTES}
max_volume_bytes = {COPY_BYTES}
max_volume_inodes = 150000
max_volumes = 2
max_records = 8
'''


def fixture(args):
    import pwd
    sys.path.insert(0, str(REPOSITORY / 'executor-service/rust/sandcrab'))
    from native_probe import call
    from volume_probe import VolumeProbe

    class CorpusFixture(VolumeProbe):
        def setup(self):
            call('mkfs.xfs', '-f', '-m', 'reflink=1', str(self.image))
            call('mount', '-o', 'loop,prjquota,nosuid,nodev', str(self.image), str(self.data))
            self.slice_file.write_text('[Slice]\nMemoryMax=1G\nMemorySwapMax=0\nTasksMax=256\nCPUQuota=100%\n')
            call('systemctl', 'daemon-reload')
            call('systemctl', 'start', self.slice)
            self.start()

    probe = CorpusFixture(args.binary, args.tools, args.report)
    probe.client = pwd.getpwnam('daemon')
    with probe.image.open('r+b') as image:
        image.truncate(POOL_BYTES)
    probe.config.write_text(configuration(probe, args.tools))
    probe.receipt.update(scope='Complete FiQA corpus through the independent TypeScript consumer and native API; no model or production data',
                         sourceRevision=args.revision, controllerSha256=sha(Path(__file__)), result='NOT_RUN')
    return probe


def stage(probe, data):
    consumer, client_data = probe.root / 'consumer', probe.root / 'client-data'
    consumer.mkdir(mode=0o755)
    consumer.chmod(0o755)
    hashes = {}
    for source in sorted((LAB / 'dist').rglob('*.js')):
        assert not source.is_symlink() and source.resolve().is_relative_to(LAB / 'dist')
        target = consumer / source.relative_to(LAB)
        target.parent.mkdir(mode=0o755, parents=True, exist_ok=True)
        shutil.copyfile(source, target)
        target.chmod(0o644)
        hashes[source.relative_to(LAB).as_posix()] = sha(target)
    assert 'dist/src/native-acceptance.js' in hashes and 'dist/test/worker-process-fixture.js' in hashes
    for directory in consumer.rglob('*'):
        if directory.is_dir():
            directory.chmod(0o755)
    (consumer / 'package.json').write_text('{"type":"module"}\n')
    (consumer / 'package.json').chmod(0o644)
    client_data.mkdir(mode=0o700)
    for name in ('corpus.bundle', 'corpus-manifest.json'):
        target = client_data / name
        shutil.copyfile(data / name, target)
        assert sha(target) == sha(data / name)
        target.chmod(0o600)
        os.chown(target, probe.client.pw_uid, probe.client.pw_gid)
    os.chown(client_data, probe.client.pw_uid, probe.client.pw_gid)
    probe.receipt['consumerFiles'] = hashes
    return consumer, client_data


def run_consumer(probe, args, consumer, data):
    from native_probe import call, http
    assert call('runuser', '-u', 'nobody', '--', 'cat', str(probe.secret)).stdout == probe.secret.read_text()
    environment = {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8', 'HOME': str(data),
                   'LAB_DATA': str(data), 'LAB_WORKER_SOCKET': str(probe.socket), 'LAB_WORKER_PROFILE': 'corpus',
                   'LAB_ACCEPTANCE_HOST_SENTINEL': str(probe.secret)}
    output_path = args.report.with_suffix('.consumer.stdout')
    with output_path.open('xb') as output, args.report.with_suffix('.consumer.stderr').open('xb') as error:
        process = subprocess.Popen([str(args.node), '--max-old-space-size=128', 'dist/src/native-acceptance.js'],
            cwd=consumer, env=environment, stdin=subprocess.DEVNULL, stdout=output, stderr=error,
            user=probe.client.pw_uid, group=probe.client.pw_gid, extra_groups=[probe.client.pw_gid], start_new_session=True)
        try:
            code = process.wait(timeout=600)
        finally:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
            process.wait(timeout=15)
    assert code == 0, 'Independent consumer failed; inspect retained stdout and stderr'
    assert output_path.stat().st_size <= 65536, 'Unexpected consumer output size'
    results = [json.loads(line) for line in output_path.read_text().splitlines()]
    assert len(results) == 3 and all(row['result'] == 'PASS' for row in results)
    assert results[0]['documents'] == 57600 and results[0]['navigation'] > 32 and results[0]['queries'] == []
    probe.receipt['consumerChecks'] = results
    for kind in ('runs', 'sessions', 'volumes'):
        status, inventory = http(probe.socket, 'GET', '/v1/' + kind)
        assert status == 200
        assert inventory['volumes' if kind == 'volumes' else 'ids'] == [], 'Consumer retained native ' + kind
        if kind != 'volumes':
            assert inventory['nextAfter'] is None
    probe.done('independent-consumer-releases-all-native-runs-sessions-and-volumes')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('binary', 'tools', 'node', 'data', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--revision', required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0 and re.fullmatch('[0-9a-f]{40}', args.revision)
    for name in ('binary', 'tools', 'node', 'data'):
        setattr(args, name, getattr(args, name).resolve(strict=True))
    args.report = args.report.absolute()
    args.report.parent.mkdir(parents=True, exist_ok=True)
    assert not any(args.report.parent.glob(args.report.stem + '.*')), 'Preserve every earlier fixture attempt'
    assert shutil.disk_usage('/var/lib').free >= 12 * 1024**3, 'Full-corpus fixture needs 12 GiB free disk'
    manifest = corpus(args.data)
    os.umask(0o077)

    def interrupted(signum, frame):
        raise TimeoutError('Native corpus fixture interrupted or exceeded its deadline')

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    probe = fixture(args)
    probe.receipt['corpus'] = {key: value for key, value in manifest.items() if key != 'documentPaths'}
    probe.receipt['profileSha256'] = sha(probe.config)
    probe.save()
    try:
        signal.alarm(900)
        probe.setup()
        consumer, data = stage(probe, args.data)
        run_consumer(probe, args, consumer, data)
        probe.receipt['result'] = 'PASS'
    except BaseException as failure:
        probe.receipt.update(result='FAIL', failureType=type(failure).__name__)
        args.report.with_suffix('.diagnostic.txt').write_text(str(failure))
        raise
    finally:
        signal.alarm(180)
        try:
            state = probe.root / 'client-data'
            for path in state.glob('native-acceptance.db*'):
                assert path.is_file() and not path.is_symlink() and path.stat().st_size <= 8 * 1024**2
                shutil.copyfile(path, args.report.with_name(args.report.stem + '-' + path.name))
            probe.cleanup()
        except BaseException as failure:
            probe.receipt.update(result='FAIL', cleanup='FAIL', cleanupFailureType=type(failure).__name__)
            raise
        finally:
            signal.alarm(0)
            probe.save()


if __name__ == '__main__':
    main()
