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
import re
from pathlib import Path
import subprocess
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
from mcp_http import Browser  # noqa: E402
from native_repository_fixture import sha  # noqa: E402


def check_clients(root, directory):
    directory.mkdir()
    passed = True
    for name, marker in (('games', 'gamesHttp'), ('account', 'accountHttp')):
        command = [sys.executable, '-u', str(HERE / (name + '-http.py')), '--fixture', str(root)]
        if name == 'account':
            command.append('--keep')
        path = directory / (name + '.log')
        with path.open('xb') as log:
            check = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=240)
        reports = [json.loads(line) for line in path.read_text().splitlines() if line.startswith('{')]
        ok = check.returncode == 0 and any(report.get(marker) == 'PASS' for report in reports)
        print(json.dumps({marker: 'PASS' if ok else 'FAIL'}), flush=True)
        passed &= ok
    return passed


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
    parser.add_argument('--verify-clients', action='store_true', help='Also run the authenticated game and account clients')
    args = parser.parse_args()
    assert os.geteuid() == 0, 'run as root, like native-host.py'
    controller = subprocess.Popen(
        [sys.executable, str(HERE / 'native-host.py'), '--runtime', str(args.runtime),
         '--binary', str(args.binary), '--tools', str(args.tools), '--java', str(args.java),
         '--agents', str(args.agents), '--port', str(args.port), '--lifetime-seconds', '1200']
        + (['--games'] if args.verify_clients else []),
        stdout=subprocess.PIPE, text=True)
    ready = None
    for line in controller.stdout:
        print(line, end='', flush=True)
        if line.startswith('{') and json.loads(line).get('ready'):
            ready = json.loads(line)
            break
    if ready is None:
        controller.wait(60)
        raise SystemExit('the native controller did not become ready')
    root = Path(ready['root'])
    status = 1
    try:
        assert ready['backend'] == 'sandcrab-native-v1' and ready['serverId']
        for field in ('binarySha256', 'nativeConfigSha256', 'runtimeManifestSha256'):
            assert re.fullmatch('[a-f0-9]{64}', ready[field])
        assert ready['binarySha256'] == sha(args.binary)
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
        if args.verify_clients and not check_clients(root, args.evidence.parent / 'clients'):
            status = 1
    finally:
        (root / 'stop').touch()
        cleanup = None
        for line in controller.stdout:
            print(line, end='', flush=True)
            if line.startswith('{'):
                cleanup = json.loads(line).get('cleanup', cleanup)
        controller.wait(120)
    if controller.returncode != 0 or cleanup != 'PASS':
        raise SystemExit('the native controller did not report cleanup: PASS')
    sys.exit(status)


if __name__ == '__main__':
    main()
