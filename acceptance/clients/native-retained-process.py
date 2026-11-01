#!/usr/bin/env python3
"""One isolated native consumer fixture, with two precisely owned application process losses."""
import argparse
import json
import os
import re
from pathlib import Path
import shutil
import signal
import subprocess
import sys

from native_app_fixture import read_private
from native_controller import ControllerOutput
from native_repository_fixture import sha
from native_retained_checks import verify

HERE = Path(__file__).resolve().parent


def execute(args, output, error):
    controller = subprocess.Popen([sys.executable, str(HERE / 'native-host.py'),
        '--runtime', str(args.runtime), '--binary', str(args.binary), '--tools', str(args.tools),
        '--java', str(args.java), '--port', str(args.port), '--agents', '1',
        '--lifetime-seconds', '900', '--retained-process'], stdout=subprocess.PIPE, stderr=error)
    observed = ControllerOutput(controller, output)
    receipt = {'kind': 'native-retained-process-consumer', 'source': 'real-Spring-PG-auth-HTTP-MCP-Sandcrab',
               'modelDriven': False, 'result': 'FAIL', 'cleanup': 'NOT_CONFIRMED', 'checks': [],
               'pid': controller.pid, 'sourceRevision': args.revision,
               'scope': 'Owned fixture app SIGKILL at acknowledged and active-command windows; not remote-push ambiguity, rename injection, expiry or production migration'}
    root = None
    try:
        receipt.update(startTicks=(Path('/proc') / str(controller.pid) / 'stat').read_text().rsplit(')', 1)[1].split()[19],
                       binarySha256=sha(args.binary), runtimeManifestSha256=sha(args.runtime / 'manifest.sha256'),
                       sourceHashes={name: sha(HERE / name) for name in ('native-host.py', 'native_app_fixture.py',
                           'native_retained_checks.py', 'native_retained_evidence.py', 'native-retained-process.py')})
        with args.evidence.with_suffix('.process.json').open('x') as identity:
            json.dump(receipt, identity, indent=2)
        ready = observed.until('ready', 300)
        assert ready['ready'] and ready['backend'] == 'sandcrab-native-v1'
        root = Path(ready['root'])
        assert root.parent == Path('/var/lib') and root.name.startswith('poketto-client-') and not root.is_symlink()
        settings = read_private(root / 'client.json')
        assert settings['root'] == str(root) and settings['appControl'] == str(root / 'app-control')
        receipt['fixture'] = {key: ready[key] for key in ('backend', 'binarySha256', 'runtimeManifestSha256',
            'nativeConfigSha256', 'fixtureSha256', 'controllerSha256')}
        verify(settings, args.evidence, receipt)
        receipt['result'] = 'PASS'
    finally:
        try:
            if root is not None and root.exists():
                try:
                    shutil.copytree(root / 'app-control', args.evidence.with_suffix('.control'))
                except OSError as failure:
                    receipt.update(result='FAIL', controlRetentionFailure=type(failure).__name__)
                finally:
                    (root / 'stop').touch()
            elif controller.poll() is None:
                controller.terminate()
            receipt['cleanup'] = observed.until('cleanup', 180)['cleanup']
            assert controller.wait(20) == 0 and receipt['cleanup'] == 'PASS'
            assert 'controlRetentionFailure' not in receipt, 'Control evidence could not be retained'
        finally:
            observed.close()
            if receipt['cleanup'] != 'PASS' or controller.returncode != 0:
                receipt['result'] = 'FAIL'
            with args.evidence.open('x', encoding='utf-8') as saved:
                json.dump(receipt, saved, indent=2)
                saved.write('\n')
            print(json.dumps(receipt), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('runtime', 'binary', 'tools', 'java', 'evidence'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--revision', required=True)
    parser.add_argument('--port', type=int, default=38189)
    args = parser.parse_args()
    assert os.geteuid() == 0 and not args.evidence.exists() and re.fullmatch('[0-9a-f]{40}', args.revision)
    os.umask(0o077)
    for name in ('runtime', 'binary', 'tools', 'java'):
        setattr(args, name, getattr(args, name).resolve(strict=True))
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Retained-process fixture interrupted; contain the original attempt')
    signal.signal(signal.SIGTERM, interrupted)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    with args.evidence.with_suffix('.attempt').open('x') as marker:
        marker.write('One native retained-process fixture; two owned app losses, no automatic rerun\n')
    with args.evidence.with_suffix('.controller.stdout').open('xb') as output, \
            args.evidence.with_suffix('.controller.stderr').open('xb') as error:
        execute(args, output, error)


if __name__ == '__main__':
    main()
