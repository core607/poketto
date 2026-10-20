#!/usr/bin/env python3
"""Measure native and Python CLI startup serially, without a bridge or application request."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import resource
import statistics
import subprocess
import sys
import time


def sample(command, environment):
    before = resource.getrusage(resource.RUSAGE_CHILDREN)
    started = time.monotonic()
    result = subprocess.run([*command, 'status'], capture_output=True, env=environment, timeout=5)
    after = resource.getrusage(resource.RUSAGE_CHILDREN)
    assert result.returncode == 2 and not result.stdout
    assert b'this command requires an admitted Poketto execution session' in result.stderr
    return {'cpuUsec': round(((after.ru_utime + after.ru_stime) - (before.ru_utime + before.ru_stime)) * 1_000_000),
            'elapsedUsec': round((time.monotonic() - started) * 1_000_000)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--reference', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    parser.add_argument('--samples', type=int, default=15)
    args = parser.parse_args()
    if sys.platform != 'linux' or not 5 <= args.samples <= 50:
        parser.error('requires Linux and between 5 and 50 samples')
    reference, native = args.reference.resolve(strict=True), args.native.resolve(strict=True)
    environment = {key: value for key, value in os.environ.items() if key not in ('POKETTO_BRIDGE', 'POKETTO_EXECUTION_ID')}
    commands = {'python': [sys.executable, '-I', str(reference)], 'native': [str(native)]}
    results = {name: [] for name in commands}
    for command in commands.values():
        sample(command, environment)
    for index in range(args.samples):
        for name in (('python', 'native') if index % 2 == 0 else ('native', 'python')):
            results[name].append(sample(commands[name], environment))
    medians = {name: {key: statistics.median(row[key] for row in rows) for key in rows[0]}
               for name, rows in results.items()}
    ratio = medians['native']['cpuUsec'] / medians['python']['cpuUsec']
    print(json.dumps({'kind': 'serial startup microbenchmark; no bridge or production load',
                      'samples': args.samples, 'medians': medians, 'nativeCpuRatio': ratio,
                      'referenceSha256': hashlib.sha256(reference.read_bytes()).hexdigest(),
                      'nativeSha256': hashlib.sha256(native.read_bytes()).hexdigest(),
                      'result': 'PASS' if ratio <= 0.1 else 'FAIL'}))
    return 0 if ratio <= 0.1 else 1


if __name__ == '__main__':
    raise SystemExit(main())
