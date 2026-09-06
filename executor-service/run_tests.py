#!/usr/bin/env python3
"""Required Linux protocol tests with an isolated, pinned Python environment and JUnit report."""
import argparse
import hashlib
import importlib.metadata
import os
from pathlib import Path
import subprocess
import sys
import time
import unittest
import venv
import xml.etree.ElementTree as ET


class ReportResult(unittest.TextTestResult):
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.cases = []
        self.started = 0

    def startTest(self, test):
        self.started = time.monotonic()
        super().startTest(test)

    def record(self, test, kind=None, detail=None):
        self.cases.append((test.id(), time.monotonic() - self.started, kind, detail))

    def addSuccess(self, test):
        super().addSuccess(test)
        self.record(test)

    def addFailure(self, test, err):
        super().addFailure(test, err)
        self.record(test, 'failure', self._exc_info_to_string(err, test))

    def addError(self, test, err):
        super().addError(test, err)
        self.record(test, 'error', self._exc_info_to_string(err, test))

    def addSkip(self, test, reason):
        super().addSkip(test, reason)
        self.record(test, 'failure', 'Required test was skipped: ' + reason)

    def addExpectedFailure(self, test, err):
        super().addExpectedFailure(test, err)
        self.record(test, 'failure', 'Required test was marked as an expected failure')

    def addUnexpectedSuccess(self, test):
        super().addUnexpectedSuccess(test)
        self.record(test, 'failure', 'Required test unexpectedly succeeded')

    def addSubTest(self, test, subtest, err):
        super().addSubTest(test, subtest, err)
        if err:
            self.record(subtest, 'failure', self._exc_info_to_string(err, test))


def requirements(source):
    pins = {}
    for line in (source / 'requirements.txt').read_text().splitlines():
        if not line or line.startswith('#'):
            continue
        package, version = line.split('==')
        pins[package] = version
    return pins


def run_suite(source, report):
    for package, version in requirements(source).items():
        if importlib.metadata.version(package) != version:
            raise RuntimeError('Required executor dependency version does not match its pin: ' + package)
    sys.path.insert(0, str(source))
    suite = unittest.defaultTestLoader.discover(str(source), pattern='test_worker.py')
    runner = unittest.TextTestRunner(verbosity=2, resultclass=ReportResult)
    result = runner.run(suite)
    if result.testsRun < 12:
        result.cases.append(('executor.required_test_count', 0, 'failure', 'Fewer than 12 required tests ran'))
    failures = sum(case[2] is not None for case in result.cases)
    root = ET.Element('testsuite', name='executorServiceTests', tests=str(len(result.cases)),
                      failures=str(failures), skipped='0')
    for name, elapsed, kind, detail in result.cases:
        classname, _, method = name.rpartition('.')
        case = ET.SubElement(root, 'testcase', classname=classname, name=method, time=f'{elapsed:.6f}')
        if kind:
            ET.SubElement(case, kind, message=detail.splitlines()[-1] if detail else kind).text = detail
    report.parent.mkdir(parents=True, exist_ok=True)
    ET.ElementTree(root).write(report, encoding='utf-8', xml_declaration=True)
    return 0 if result.wasSuccessful() and not result.skipped and not failures else 1


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--environment', type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--suite', action='store_true')
    parser.add_argument('--wheelhouse', type=Path)
    args = parser.parse_args()
    if not sys.platform.startswith('linux'):
        raise SystemExit('executorServiceTests requires Linux; Windows must use the required Docker gate')
    source = Path(__file__).resolve().parent
    report = args.report.resolve()
    if args.suite:
        raise SystemExit(run_suite(source, report))
    if args.environment is None:
        parser.error('--environment is required when preparing dependencies')
    environment = args.environment.resolve()
    python = environment / 'bin/python'
    marker = environment / '.executor-requirements.sha256'
    identity = hashlib.sha256((source / 'requirements.txt').read_bytes() + sys.version.encode()).hexdigest()
    if not python.exists():
        venv.EnvBuilder(with_pip=True).create(environment)
    if not marker.exists() or marker.read_text() != identity:
        index = ['--no-index', '--find-links', str(args.wheelhouse.resolve())] if args.wheelhouse else ['--index-url', 'https://pypi.org/simple']
        subprocess.run([str(python), '-I', '-m', 'pip', '--isolated', '--require-virtualenv',
            '--disable-pip-version-check', 'install', '--no-deps', '--only-binary=:all:',
            *index, '--timeout', '30', '--retries', '2',
            '-r', str(source / 'requirements.txt')], check=True, timeout=180)
        marker.write_text(identity)
    result = subprocess.run([str(python), '-I', str(source / 'run_tests.py'), '--suite', '--report', str(report)],
                            timeout=60, env={**os.environ, 'PYTHONDONTWRITEBYTECODE': '1'})
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
