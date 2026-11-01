#!/usr/bin/env python3
"""Installed content tools through the public native API, with a bounded disposable sandbox."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / 'executor-service/rust/sandcrab'))
from native_probe import Probe


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('binary', 'tools', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    assert os.geteuid() == 0 and not args.report.exists(), 'Use a new report path as root'

    def interrupted(signum, frame):
        raise TimeoutError('Toolkit work or cleanup exceeded its deadline')

    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGALRM, interrupted)
    probe = Probe(args.binary.resolve(strict=True), args.tools.resolve(strict=True), args.report.resolve())
    code = (HERE / 'sandbox_toolkit.py').read_text()
    probe.config.write_text(probe.config.read_text()
        .replace('memory_bytes = 67108864', 'memory_bytes = 201326592')
        .replace('timeout_millis = 10000', 'timeout_millis = 30000'))
    probe.receipt.update(scope='Installed image, PDF, Office, HTML, archive and Git tools through native runs; no production content',
        controllerSha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        toolkitSha256=hashlib.sha256(code.encode()).hexdigest(), result='NOT_RUN')
    probe.save()
    try:
        signal.alarm(120)
        probe.setup()
        probe.receipt['profile'] = probe.info['profiles']['python']
        assert probe.receipt['profile']['memoryBytes'] == 201326592
        assert probe.receipt['profile']['timeoutMillis'] == 30000
        request = probe.submit(code)
        result = probe.wait(request)
        assert result['state'] == 'succeeded' and result['result']['exitCode'] == 0, result
        observed = json.loads(result['result']['stdout'])
        assert observed == {'result': 'PASS', 'checks': ['image-font-webp', 'html-xml', 'docx', 'xlsx',
                                                       'pdf-copy-render', 'zip', 'awk', 'git']}
        probe.receipt['toolkit'] = observed
        probe.forget(request)
        probe.done('native-content-toolkit')
        probe.receipt['result'] = 'PASS'
    except BaseException as failure:
        probe.receipt.update(result='FAIL', failureType=type(failure).__name__)
        args.report.with_suffix('.diagnostic.txt').write_text(str(failure))
        raise
    finally:
        signal.alarm(90)
        try:
            probe.cleanup()
        finally:
            signal.alarm(0)
            probe.save()


if __name__ == '__main__':
    main()
