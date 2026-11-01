"""Offline barrier observation and owned signal checks, without starting a native or systemd host."""
import copy
import hashlib
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'executor-service/rust/sandcrab'))
from durability_probe_evidence import barrier, disk_checkpoint, frame, native_checkpoint
from durability_probe_host import kill


class DurabilityObservationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.writer = {'workerBootId': 'epoch', 'leaseId': 'session', 'storage': {'volumeId': 'copy'}}
        self.before = {'copyId': 'copy', 'writer': self.writer, 'phase': 'RUNNING', 'executionId': 'command',
                       'revision': 3, 'state': {'baseCommit': 'base', 'uncertain': True,
                       'attempt': {'commit': 'candidate'}, 'fileBaselines': {'p': {'content': 'acknowledged'}}}}
        self.ready = {'scenario': 'uncertain', 'pid': 123, 'probeClassSha256': 'probe', 'executorClassSha256': 'executor',
                      'writer': self.writer, 'copyId': 'copy', 'phase': 'RUNNING', 'executionId': 'command',
                      'commandId': 'command', 'candidateCommit': 'candidate'}
        self.classes = {'probe': 'probe', 'executor': 'executor'}

    @staticmethod
    def write_frame(path, value):
        data = json.dumps(value).encode()
        digest = hashlib.sha256(data).digest()
        path.write_bytes(b'PKRT0001' + struct.pack('>q', len(data)) + data + digest)
        return digest.hex()

    def test_disk_checkpoint_requires_actual_matching_publication_and_immutable_bytes(self):
        key = 'a' * 64
        baseline = self.root / (key + '.baseline')
        baseline.write_bytes(b'original immutable archive bytes')
        self.before['original'] = {'bytes': baseline.stat().st_size,
                                   'sha256': hashlib.sha256(baseline.read_bytes()).hexdigest()}
        versions = self.root / 'versions'
        digest = self.write_frame(versions, {'copyId': 'copy', 'files': self.before['state']['fileBaselines']})
        versions.rename(self.root / (key + '.acknowledged-' + digest))
        stored = copy.deepcopy(self.before)
        stored['format'] = 2
        stored['state']['fileBaselines'] = digest
        target = self.root / (key + '.account')
        self.write_frame(target, stored)
        self.assertEqual(disk_checkpoint(self.root, self.before)['phase'], 'RUNNING')
        stored['phase'] = 'READY'
        self.write_frame(target, stored)
        with self.assertRaises(AssertionError):
            disk_checkpoint(self.root, self.before)
        stored['phase'] = 'RUNNING'
        self.write_frame(target, stored)
        baseline.write_bytes(b'changed immutable archive bytes!')
        with self.assertRaises(AssertionError):
            disk_checkpoint(self.root, self.before)

    def test_record_parser_rejects_truncation_and_changed_payload_before_json_read(self):
        target = self.root / 'record'
        self.write_frame(target, {'copyId': 'copy'})
        original = target.read_bytes()
        self.assertEqual(frame(target), {'copyId': 'copy'})
        for value in (original[:-1], original + b'extra', original[:20] + b'X' + original[21:]):
            target.write_bytes(value)
            with self.assertRaises(AssertionError):
                frame(target)

    def test_uncertain_checkpoint_requires_matching_process_epoch_and_real_attempt(self):
        barrier(self.ready, self.before, 'uncertain', 123, 'epoch', self.classes)
        for field, value in (('pid', 999), ('candidateCommit', 'another'), ('phase', 'READY'),
                             ('executorClassSha256', 'another'), ('commandId', None)):
            changed = {**self.ready, field: value}
            with self.assertRaises(AssertionError):
                barrier(changed, self.before, 'uncertain', 123, 'epoch', self.classes)

    def test_before_and_after_publication_cannot_share_the_same_disk_phase(self):
        for scenario, phase, execution in (('beforepublish', 'RUNNING', 'command'), ('afterpublish', 'READY', None)):
            ready = {**self.ready, 'scenario': scenario, 'phase': phase, 'executionId': execution, 'candidateCommit': None}
            before = {**self.before, 'phase': phase, 'executionId': execution}
            barrier(ready, before, scenario, 123, 'epoch', self.classes)
            with self.assertRaises(AssertionError):
                barrier(ready, {**before, 'phase': 'READY' if phase == 'RUNNING' else 'RUNNING'},
                        scenario, 123, 'epoch', self.classes)

    def test_native_command_and_volume_observations_distinguish_running_completed_and_deleted(self):
        session = {'id': 'session', 'serverId': 'epoch', 'state': 'busy', 'activeRunId': 'command'}
        volume = {'id': 'copy', 'state': 'ready', 'attachedTo': 'session', 'busy': False}
        run = {'id': 'command', 'state': 'running'}
        native_checkpoint('uncertain', self.ready, session, volume, run)
        with self.assertRaises(AssertionError):
            native_checkpoint('beforepublish', self.ready, session, volume, run)
        session.update(state='ready', activeRunId=None)
        run.update(state='succeeded', result={'exitCode': 0})
        native_checkpoint('beforepublish', self.ready, session, volume, run)
        native_checkpoint('afterpublish', self.ready, session, volume, run)
        with self.assertRaises(AssertionError):
            native_checkpoint('uncertain', self.ready, session, volume, run)
        with self.assertRaises(AssertionError):
            native_checkpoint('discarding', self.ready, None, volume, None)
        volume.update(state='deleted', attachedTo=None)
        native_checkpoint('discarding', self.ready, None, volume, None)

    @unittest.skipUnless(hasattr(os, 'pidfd_open'), 'Linux pidfd required')
    def test_stale_identity_cannot_signal_then_exact_owned_child_receives_sigkill(self):
        child = subprocess.Popen([sys.executable, '-c', 'import time; time.sleep(60)'])
        self.addCleanup(lambda: child.poll() is None and child.kill())
        expected = {'pid': child.pid, 'startTicks': 'known'}
        with patch('durability_probe_host.identity', return_value={**expected, 'startTicks': 'other'}):
            with self.assertRaises(AssertionError):
                kill('owned-fixture.service', Path('/fixture-java'), expected)
        self.assertIsNone(child.poll())
        with patch('durability_probe_host.identity', return_value=expected):
            kill('owned-fixture.service', Path('/fixture-java'), expected)
        self.assertEqual(child.wait(timeout=5), -signal.SIGKILL)


if __name__ == '__main__':
    unittest.main()
