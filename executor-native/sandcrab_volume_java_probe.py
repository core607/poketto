#!/usr/bin/env python3
"""Java public-API consumer on a disposable native XFS fixture; no product or model calls."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import signal
import subprocess
import sys

NATIVE_SOURCE = Path(__file__).resolve().parents[1] / 'executor-service/rust/sandcrab'
sys.path.insert(0, str(NATIVE_SOURCE))
import native_probe
from volume_probe import VolumeProbe


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(65536), b''):
            digest.update(block)
    return digest.hexdigest()


def verify_runtime(runtime):
    paths = list(runtime.rglob('*'))
    assert all(not path.is_symlink() and (path.is_file() or path.is_dir()) for path in paths), \
        'Runtime staging must contain only ordinary files and directories'
    entries = {}
    for line in (runtime / 'manifest.sha256').read_text().splitlines():
        digest, name = line.split('  ', 1)
        relative = Path(name)
        assert not relative.is_absolute() and '..' not in relative.parts
        assert name not in entries
        path = runtime / relative
        assert path.is_file() and not path.is_symlink()
        assert path.resolve().is_relative_to(runtime)
        assert sha256(path) == digest, 'Runtime bytes differ from the build manifest'
        entries[name] = digest
    actual = {path.relative_to(runtime).as_posix() for path in paths
              if path.is_file() and path != runtime / 'manifest.sha256'}
    assert actual == entries.keys(), 'Runtime has unlisted or missing files'
    return sha256(runtime / 'manifest.sha256')


def seed_bundle(probe, consumer):
    repository = probe.root / 'seed-repository'
    repository.mkdir(mode=0o700)
    payload = random.Random(260627).randbytes(2 * 1024 * 1024)
    (repository / 'payload.bin').write_bytes(payload)
    environment = {'PATH': '/usr/bin:/bin', 'HOME': str(probe.root), 'GIT_CONFIG_NOSYSTEM': '1',
                   'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_AUTHOR_DATE': '2026-01-01T00:00:00Z',
                   'GIT_COMMITTER_DATE': '2026-01-01T00:00:00Z'}

    def git(*arguments):
        return subprocess.run(['git', '-C', str(repository), '-c', 'core.hooksPath=/dev/null',
                               '-c', 'user.name=Sandcrab fixture', '-c', 'user.email=fixture@example.invalid',
                               *arguments], env=environment, check=True, capture_output=True, text=True,
                              timeout=20).stdout.strip()

    git('init', '--quiet', '--initial-branch=main')
    git('add', 'payload.bin')
    git('commit', '--quiet', '-m', 'test: initialize synthetic repository')
    bundle = consumer / 'seed.bundle'
    git('bundle', 'create', str(bundle), 'HEAD')
    assert bundle.stat().st_size > 1024 * 1024
    bundle.chmod(0o600)
    os.chown(bundle, *native_probe.CLIENT)
    return bundle, git('rev-parse', 'HEAD'), hashlib.sha256(payload).hexdigest()


def java_phase(probe, java, runtime, consumer, phase, extra=()):
    unit = probe.name + 'j-' + phase + '.service'
    command = ['systemd-run', '--quiet', '--wait', '--pipe', '--collect', '--unit', unit,
               '--slice', probe.slice, '-p', 'User=daemon', '-p', 'Group=daemon',
               '-p', 'MemoryMax=192M', '-p', 'MemorySwapMax=0', '-p', 'TasksMax=64',
               '-p', 'CPUQuota=50%', '-p', 'RuntimeMaxSec=120', '-p', 'TimeoutStopSec=10',
               '-p', 'KillMode=control-group', '-p', 'UMask=0077', '-p', 'Environment=LANG=C.UTF-8',
               str(java), '-Xms16m', '-Xmx96m', '-XX:MaxMetaspaceSize=128m', '-XX:ActiveProcessorCount=1',
               '-cp', str(runtime / 'classes') + ':' + str(runtime / 'jars/*'),
               'io.github.core607.poketto.executor.internal.SandcrabVolumeNativeProbe',
               str(probe.socket), phase, str(consumer / 'state.json'), *map(str, extra)]
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=150)
        probe.report.with_suffix('.' + phase + '.stdout').write_text(result.stdout)
        probe.report.with_suffix('.' + phase + '.stderr').write_text(result.stderr)
        assert result.returncode == 0, 'Java consumer failed; retained stdout/stderr identify the phase'
        receipt = json.loads(result.stdout)
        assert receipt['result'] == 'PASS'
        return receipt
    finally:
        native_probe.call('systemctl', 'stop', unit, check=False)
        active = native_probe.call('systemctl', 'show', unit, '-p', 'ActiveState', '--value', check=False).stdout.strip()
        assert active in ('', 'inactive', 'failed'), 'Java fixture process did not stop'
        native_probe.call('systemctl', 'reset-failed', unit, check=False)


def run(args):
    runtime_hash = verify_runtime(args.runtime)
    probe = VolumeProbe(args.binary, args.tools, args.report)
    probe.receipt.update(scope='Java public API, streamed synthetic Git bundle, durable volumes and restart; no product cutover or model calls',
                         controllerSha256=sha256(Path(__file__)), runtimeManifestSha256=runtime_hash)
    try:
        probe.setup()
        runtime = probe.root / 'java-runtime'
        shutil.copytree(args.runtime, runtime)
        for path in [runtime, *runtime.rglob('*')]:
            path.chmod(0o755 if path.is_dir() else 0o644)
        consumer = probe.root / 'consumer'
        consumer.mkdir(mode=0o700)
        os.chown(consumer, *native_probe.CLIENT)
        bundle, commit, file_digest = seed_bundle(probe, consumer)
        probe.receipt['corpus'] = {'commit': commit, 'bundleSha256': sha256(bundle),
                                   'bundleBytes': bundle.stat().st_size, 'fileSha256': file_digest}
        prepared = java_phase(probe, args.java, runtime, consumer, 'prepare', (bundle, commit, file_digest))
        assert len(prepared['checks']) == 2
        probe.receipt['prepare'] = prepared
        probe.save()
        before = probe.info['serverId']
        probe.restart()
        assert probe.info['serverId'] != before
        resumed = java_phase(probe, args.java, runtime, consumer, 'resume')
        assert len(resumed['checks']) == 4
        probe.receipt['resume'] = resumed
        probe.receipt['result'] = 'PASS'
        probe.done('java-streamed-repository-volume-restart-and-clone')
    except BaseException as error:
        probe.receipt['result'] = 'FAIL'
        probe.receipt['failureType'] = type(error).__name__
        args.report.with_suffix('.diagnostic.txt').write_text(str(error) + '\n' +
            native_probe.call('journalctl', '-u', probe.unit, '--no-pager', '-n', '60', check=False).stdout)
        raise
    finally:
        try:
            probe.cleanup()
        finally:
            probe.save()


def main():
    parser = argparse.ArgumentParser()
    for name in ('binary', 'tools', 'java', 'runtime', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    for name, value in vars(args).items():
        setattr(args, name, value.resolve())
    assert os.geteuid() == 0
    assert not args.report.exists(), 'Preserve earlier probe attempts'
    args.report.parent.mkdir(parents=True, exist_ok=True)

    def interrupted(signum, frame):
        raise KeyboardInterrupt('Java volume probe interrupted')

    signal.signal(signal.SIGTERM, interrupted)
    run(args)


if __name__ == '__main__':
    main()
