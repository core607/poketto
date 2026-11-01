"""Offline workload checks: real shell exit status and comparison refusal before any remote call."""
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location('load_suite_under_test', HERE / 'load-suite.py')
suite = importlib.util.module_from_spec(spec)
spec.loader.exec_module(suite)
load = suite.load


@unittest.skipUnless(shutil.which('sh'), 'Requires a POSIX shell')
class ShellOutcomeTests(unittest.TestCase):
    def shell(self, command, exit_code):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            executable = root / 'poketto'
            executable.write_text('#!/bin/sh\nprintf "%s\\n" "$*" > "$LOAD_ARGUMENTS"\n'
                                  'i=0; while test "$i" -lt 400; do printf data; i=$((i+1)); done\n'
                                  'exit "$LOAD_EXIT"\n')
            executable.chmod(0o755)
            arguments = root / 'arguments'
            result = subprocess.run(['sh', '-c', command], capture_output=True, timeout=5,
                env={**os.environ, 'PATH': str(root) + os.pathsep + os.environ.get('PATH', ''),
                     'LOAD_ARGUMENTS': str(arguments), 'LOAD_EXIT': str(exit_code)})
            return result, arguments.read_text().strip()

    def test_remote_status_is_bounded_without_turning_a_cli_failure_into_success(self):
        success, arguments = self.shell(load.READS['status'], 0)
        self.assertEqual(arguments, 'status --remote')
        self.assertEqual(success.returncode, 0)
        self.assertEqual(len(success.stdout), 300)
        failed, _ = self.shell(load.READS['status'], 7)
        self.assertEqual(failed.returncode, 7)

    def test_save_and_cleanup_observe_the_real_cli_exit(self):
        class Endpoint:
            command = None

            def repo_exec(self, copy, command):
                self.command = command
                return 'EXIT_7', copy

        for operation in ('poketto save private/item.md', 'poketto save --delete private/item.md'):
            result, arguments = self.shell(load.bounded_output(operation, 120), 7)
            self.assertEqual(result.returncode, 7)
            self.assertEqual(arguments, operation.removeprefix('poketto '))
        args = type('Arguments', (), {'seconds': 1, 'saves': 1})()
        endpoint = Endpoint()
        run = load.Run(args, endpoint)
        run.copies = {1: 'fixture-copy'}
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            path = root / 'private/.loadtest/agent-1.md'
            path.parent.mkdir(parents=True)
            path.write_text('probe')
            self.assertEqual(run.cleanup(), 0)
            command = endpoint.command.replace('private/.loadtest/agent-1.md', str(path))
            result, arguments = self.shell(command, 7)
            self.assertEqual(result.returncode, 7)
            self.assertTrue(arguments.startswith('save --delete '))


class BaselineTests(unittest.TestCase):
    def test_mismatched_or_older_baselines_are_refused_before_token_read_or_connection(self):
        settings = {'seconds': 180, 'warmup': 60.0, 'think': [3.0, 10.0], 'thinkDistribution': 'uniform',
                    'warmUpRun': [10, 20], 'timeout': 75.0}
        changed = load.workload()
        changed['reads']['status'] = 'poketto status'
        variants = [
            {'stages': {}},
            {'workload': changed, 'settings': settings},
            {'workload': load.workload(), 'settings': {**settings, 'think': [1.0, 3.0]}},
        ]
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            baseline = root / 'baseline.json'
            for value in variants:
                with self.subTest(value=value):
                    baseline.write_text(json.dumps(value))
                    argv = ['load-suite.py', '--endpoint', 'http://127.0.0.1:1/mcp',
                            '--token-file', str(root / 'absent-token'), '--baseline', str(baseline),
                            '--evidence', str(root / 'not-created.json')]
                    with patch.object(sys, 'argv', argv), patch.object(load, 'connect') as connect:
                        with self.assertRaisesRegex(SystemExit, 'baseline workload or sampling settings differ'):
                            suite.main()
                        connect.assert_not_called()
                    self.assertFalse((root / 'not-created.json').exists())
            value = {'workload': load.workload(), 'settings': settings, 'stages': {'8': {'p95': .2}}}
            baseline.write_text(json.dumps(value))
            self.assertEqual(suite.read_baseline(baseline, settings), value)


if __name__ == '__main__':
    unittest.main()
