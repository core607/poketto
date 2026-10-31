"""Offline control and observation boundaries; no systemd/native/consumer host is started."""
import copy
from contextlib import nullcontext
import hashlib
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import tempfile
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from native_app_fixture import OwnedApplication, kill_exact, publish, read_private
from native_retained_evidence import frame, preserved


class AppControlTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.owner = OwnedApplication(self.root, 'fixture-app', [], 'http://127.0.0.1:1', time.monotonic() + 900, True)
        self.owner.identity = {'unit': 'fixture-app.service', 'pid': 123, 'startTicks': '456', 'cgroup': '/fixture'}

    def test_control_publication_is_private_and_never_overwrites_a_prior_result(self):
        target = self.root / 'reply.json'
        publish(target, {'sequence': 1})
        self.assertEqual(read_private(target), {'sequence': 1})
        with self.assertRaises(FileExistsError):
            publish(target, {'sequence': 2})
        self.assertEqual(read_private(target), {'sequence': 1})

    def test_stale_identity_wrong_action_and_out_of_order_request_never_signal(self):
        original = {'sequence': 1, 'action': 'restart-app', 'expected': self.owner.identity}
        for change in ({'sequence': True}, {'sequence': 2}, {'action': 'restart-native'},
                       {'expected': {**self.owner.identity, 'startTicks': 'old'}}):
            target = self.owner.control / 'request-1.json'
            publish(target, {**original, **change})
            with patch('native_app_fixture.kill_exact') as kill:
                with self.assertRaises(AssertionError):
                    self.owner.poll()
                kill.assert_not_called()
            target.unlink()

    def test_restart_is_claimed_once_then_contains_old_process_before_starting(self):
        publish(self.owner.control / 'request-1.json', {'sequence': 1, 'action': 'restart-app',
                                                     'expected': self.owner.identity})
        old = self.owner.identity.copy()
        order = []
        current = {**old, 'unit': 'fixture-app-r1.service', 'pid': 789, 'startTicks': '900'}
        def start():
            order.append('start')
            self.owner.identity = current
            return current
        with (patch('native_app_fixture.kill_exact', side_effect=lambda value: order.append('kill')),
                patch('native_app_fixture.contained', side_effect=lambda value: order.append('contained')),
                patch.object(self.owner, 'start', side_effect=start)):
            self.owner.poll()
        self.assertEqual(order, ['kill', 'contained', 'start'])
        reply = read_private(self.owner.control / 'reply-1.json')
        self.assertEqual(reply['old'], old)
        self.assertEqual(reply['current'], current)
        self.assertTrue(reply['appContained'])
        self.assertTrue((self.owner.control / 'claimed-1.json').exists())

    def test_original_lifetime_and_two_restart_limit_cannot_be_extended(self):
        for sequence, remaining in ((0, 20), (2, 900)):
            self.owner.sequence = sequence
            self.owner.deadline = time.monotonic() + remaining
            publish(self.owner.control / ('request-' + str(sequence + 1) + '.json'),
                    {'sequence': sequence + 1, 'action': 'restart-app', 'expected': self.owner.identity})
            with patch('native_app_fixture.kill_exact') as kill:
                with self.assertRaises(AssertionError):
                    self.owner.poll()
                kill.assert_not_called()

    def test_each_replacement_unit_receives_only_the_remaining_original_lifetime(self):
        self.owner.deadline = 1000
        commands = []
        def run(command):
            commands.append(command)
            return 'Synthetic acceptance services are ready' if command[0] == 'journalctl' else ''
        def identity(unit):
            return {**self.owner.identity, 'unit': unit}
        with (patch('native_app_fixture.run', side_effect=run),
              patch('native_app_fixture.process_identity', side_effect=identity),
              patch('native_app_fixture.urllib.request.urlopen', return_value=nullcontext(SimpleNamespace(status=200)))):
            with patch('native_app_fixture.time.monotonic', return_value=100):
                self.owner.start()
            self.owner.sequence = 1
            with patch('native_app_fixture.time.monotonic', return_value=250):
                self.owner.start()
        launches = [command for command in commands if command[0] == 'systemd-run']
        self.assertIn('RuntimeMaxSec=900', launches[0])
        self.assertIn('RuntimeMaxSec=750', launches[1])
        self.assertNotEqual(launches[0][4], launches[1][4])

    @unittest.skipUnless(hasattr(os, 'pidfd_open'), 'Linux pidfd required')
    def test_pidfd_kills_only_the_observed_child_and_stale_readback_cannot_signal(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        self.addCleanup(lambda: child.poll() is None and child.kill())
        expected = {**self.owner.identity, 'pid': child.pid}
        with patch('native_app_fixture.process_identity', return_value={**expected, 'startTicks': 'changed'}):
            with self.assertRaises(AssertionError):
                kill_exact(expected)
        self.assertIsNone(child.poll())
        with patch('native_app_fixture.process_identity', return_value=expected):
            kill_exact(expected)
        self.assertEqual(child.wait(timeout=5), -signal.SIGKILL)


class JournalEvidenceTests(unittest.TestCase):
    def test_actual_record_framing_rejects_truncation_changed_checksum_and_extra_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / 'record'
            body = json.dumps({'format': 2, 'copyId': 'fixture'}).encode()
            raw = b'PKRT0001' + struct.pack('>q', len(body)) + body + hashlib.sha256(body).digest()
            path.write_bytes(raw)
            self.assertEqual(frame(path)['copyId'], 'fixture')
            for invalid in (raw[:-1], raw + b'extra', raw[:-1] + bytes((raw[-1] ^ 1,))):
                path.write_bytes(invalid)
                with self.assertRaises(AssertionError):
                    frame(path)

    def test_recovery_must_preserve_original_and_acknowledgement_and_name_the_unknown_command(self):
        old = {'owner': {'accountId': 'account'}, 'copyId': 'copy', 'state': {'lastSave': {'ok': True}},
               'original': {'sha256': 'original'}, 'publicExport': None, 'phase': 'RUNNING',
               'executionId': 'unknown-command', 'lastInterruptedCommand': None, 'revision': 1, 'expiresAt': 50,
               'writer': {'principalId': 'grant', 'appBootId': 'old', 'workerBootId': 'epoch',
                          'leaseId': 'old-session', 'storage': {'volumeId': 'copy'}}}
        before = {'record': old, 'baselines': {'draft': {'content': 'acknowledged'}}, 'originalSha256': 'original'}
        after = copy.deepcopy(before)
        after['record'].update(phase='READY', executionId=None, lastInterruptedCommand='unknown-command', revision=2)
        after['record']['writer'].update(appBootId='new', leaseId='new-session')
        preserved(before, after, True)
        for area, key, replacement in (('record', 'lastInterruptedCommand', None),
                                       ('record', 'state', {'lastSave': {}}),
                                       ('record', 'original', {'sha256': 'changed'}),
                                       (None, 'baselines', {})):
            invalid = copy.deepcopy(after)
            (invalid if area is None else invalid[area])[key] = replacement
            with self.assertRaises(AssertionError):
                preserved(before, invalid, True)


if __name__ == '__main__':
    unittest.main()
