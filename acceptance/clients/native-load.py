#!/usr/bin/env python3
"""A judged small agent load on disposable native services: native-host.py, a fresh key, load-suite.py.

Run as root on a Linux systemd host prepared as native-host.py describes. It starts that controller
with room for --agents, issues one full key through the real login and administration endpoints,
runs one judged load-suite.py stage, then stops the controller and requires its cleanup. The exit
status is nonzero when the stage fails its requirement or the cleanup does not pass.
"""
import argparse
import json
import os
import signal
from pathlib import Path
import subprocess
import sys
from native_controller import ControllerOutput
from native_repository_fixture import sha

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from mcp_http import Browser  # noqa: E402


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    for name in ('runtime', 'binary', 'tools', 'java'):
        parser.add_argument('--' + name, type=Path, required=True)
    parser.add_argument('--agents', type=int, default=8)
    parser.add_argument('--seconds', type=int, default=90)
    parser.add_argument('--warmup', type=float, default=30.0)
    parser.add_argument('--think', type=float, nargs=2, default=(1.0, 3.0), metavar=('MIN', 'MAX'))
    parser.add_argument('--require', help='AGENTS:OKRATE:P95; defaults to AGENTS:0.99:1.0')
    parser.add_argument('--port', type=int, default=38189)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0, 'run as root, like native-host.py'
    assert 1 <= args.agents <= 64 and 1 <= args.seconds <= 300 and 0 <= args.warmup < args.seconds
    assert 0 <= args.think[0] <= args.think[1] and args.think[1] > 0
    assert not args.evidence.exists(), 'Preserve the previous load receipt'
    def interrupted(signum, frame):
        raise KeyboardInterrupt('Native acceptance interrupted; containing the original fixture')
    signal.signal(signal.SIGTERM, interrupted)
    args.evidence.parent.mkdir(parents=True, exist_ok=True)
    with args.evidence.with_suffix('.attempt').open('x') as marker:
        marker.write('One native consumer fixture and one judged load stage\n')
    with args.evidence.with_suffix('.controller.stdout').open('xb') as output, \
            args.evidence.with_suffix('.controller.stderr').open('xb') as error:
        status = execute(args, output, error)
    sys.exit(status)


def execute(args, output, error):
    controller = subprocess.Popen(
        [sys.executable, str(HERE / 'native-host.py'), '--runtime', str(args.runtime),
         '--binary', str(args.binary), '--tools', str(args.tools), '--java', str(args.java),
         '--agents', str(args.agents), '--port', str(args.port), '--lifetime-seconds', '1200'],
        stdout=subprocess.PIPE, stderr=error)
    observed = ControllerOutput(controller, output)
    receipt = {'pid': controller.pid, 'result': 'FAIL', 'cleanup': 'NOT_CONFIRMED'}
    root = None
    try:
        receipt['startTicks'] = (Path('/proc') / str(controller.pid) / 'stat').read_text().rsplit(')', 1)[1].split()[19]
        receipt.update(controllerSha256=sha(HERE / 'native-host.py'), binarySha256=sha(args.binary),
                       runtimeManifestSha256=sha(args.runtime / 'manifest.sha256'))
        with args.evidence.with_suffix('.process.json').open('x') as process_identity:
            json.dump(receipt, process_identity, indent=2)
            process_identity.write('\n')
        ready = observed.until('ready', 300)
        assert ready['ready'] and ready['backend'] == 'sandcrab-native-v1'
        root = Path(ready['root'])
        assert root.parent == Path('/var/lib') and root.name.startswith('poketto-client-')
        receipt['fixture'] = {key: ready[key] for key in (
            'backend', 'binarySha256', 'nativeConfigSha256', 'fixtureSha256', 'controllerSha256', 'runtimeManifestSha256')}
        subprocess.run([sys.executable, str(HERE / 'native-smoke.py'), '--fixture', str(root),
                        '--evidence', str(args.evidence.with_suffix('.consumer.json'))], check=True, timeout=300)
        client = json.loads((root / 'client.json').read_text())
        browser = Browser(client['endpoint'], client['password'])
        token = root / 'load-token'
        token.touch(mode=0o600)
        token.write_text(browser.key(['READ_PRIVATE', 'WRITE_PRIVATE', 'EXECUTE_REPOSITORY'])['token'])
        status = subprocess.run(
            [sys.executable, str(HERE.parent / 'load-suite.py'), '--endpoint', client['endpoint'] + '/mcp',
             '--token-file', str(token), '--stages', str(args.agents), '--seconds', str(args.seconds),
             '--warmup', str(args.warmup), '--think', str(args.think[0]), str(args.think[1]),
             '--warm-up-seconds', '0', '--require', args.require or f'{args.agents}:0.99:1.0',
             '--label', 'native-sandcrab-fixture', '--evidence', str(args.evidence)], timeout=args.seconds + 600).returncode
        receipt['result'] = 'PASS' if status == 0 else 'FAIL'
        return status
    finally:
        try:
            if root is not None and root.exists():
                (root / 'stop').touch()
            elif controller.poll() is None:
                controller.terminate()
            receipt['cleanup'] = observed.until('cleanup', 180)['cleanup']
            code = controller.wait(20)
            assert code == 0 and receipt['cleanup'] == 'PASS', 'Native controller cleanup was not confirmed'
        finally:
            observed.close()
            if receipt['cleanup'] != 'PASS' or controller.returncode != 0:
                receipt['result'] = 'FAIL'
            with args.evidence.with_suffix('.fixture.json').open('x') as saved:
                json.dump(receipt, saved, indent=2)
                saved.write('\n')


if __name__ == '__main__':
    main()
