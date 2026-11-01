"""Offline checks of configuration, staged bytes and controller framing; no host fixture."""
import hashlib
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import tomllib
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from native_controller import ControllerOutput
from native_repository_fixture import configuration, stop_unit, verify_runtime


class NativeFixtureTests(unittest.TestCase):
    def test_profile_and_volume_limits_are_native_and_keep_control_metadata_outside_data(self):
        for agents in (1, 8, 64):
            value = tomllib.loads(configuration(Path('/fixture'), Path('/tools'),
                SimpleNamespace(pw_uid=123, pw_gid=456), 'fixture-exec', 'fixture-native', 'fixture.slice', agents))
            self.assertEqual(set(value), {'service', 'storage', 'profiles'})
            service = value['service']
            self.assertEqual(service['supervisor_unit'], 'fixture-native.service')
            self.assertEqual(service['unit_prefix'], 'fixture-native-')
            self.assertEqual(service['max_sandboxes'], max(4, agents))
            self.assertLessEqual(service['max_event_streams'], min(128, service['max_connections'] - 4))
            self.assertTrue(value['profiles']['repository']['host_calls'])
            self.assertEqual(value['storage']['root'], '/fixture/native-storage')
            self.assertFalse(service['runtime_root'].startswith(value['storage']['root']))
            self.assertLessEqual(value['storage']['max_volume_bytes'], value['storage']['pool_bytes'])
            self.assertNotIn('signing', str(value))

    def test_manifest_refuses_changed_unlisted_and_duplicate_runtime_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / 'program.class'
            source.write_bytes(b'compiled fixture')
            line = hashlib.sha256(source.read_bytes()).hexdigest() + '  program.class\n'
            manifest = root / 'manifest.sha256'
            manifest.write_text(line)
            self.assertEqual(verify_runtime(root), hashlib.sha256(manifest.read_bytes()).hexdigest())
            source.write_bytes(b'changed fixture')
            with self.assertRaises(AssertionError):
                verify_runtime(root)
            source.write_bytes(b'compiled fixture')
            (root / 'unexpected').write_bytes(b'unlisted')
            with self.assertRaises(AssertionError):
                verify_runtime(root)
            (root / 'unexpected').unlink()
            manifest.write_text(line + line)
            with self.assertRaises(AssertionError):
                verify_runtime(root)

    def test_manifest_refuses_escaping_paths_even_with_a_matching_digest(self):
        with tempfile.TemporaryDirectory() as temporary:
            parent = Path(temporary)
            outside = parent / 'outside'
            outside.write_bytes(b'outside')
            root = parent / 'runtime'
            root.mkdir()
            (root / 'manifest.sha256').write_text(hashlib.sha256(outside.read_bytes()).hexdigest() + '  ../outside\n')
            with self.assertRaises(AssertionError):
                verify_runtime(root)

    def test_nested_manifest_is_payload_and_must_be_listed(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            nested = root / 'classes/manifest.sha256'
            nested.parent.mkdir()
            nested.write_bytes(b'nested application resource')
            manifest = root / 'manifest.sha256'
            manifest.write_text('')
            with self.assertRaises(AssertionError):
                verify_runtime(root)
            manifest.write_text(hashlib.sha256(nested.read_bytes()).hexdigest() + '  classes/manifest.sha256\n')
            self.assertEqual(verify_runtime(root), hashlib.sha256(manifest.read_bytes()).hexdigest())

    def test_missing_unit_is_distinguished_from_an_unavailable_system_manager(self):
        result = SimpleNamespace(stdout='LoadState=not-found\nActiveState=inactive\n')
        with patch('native_repository_fixture.subprocess.run', return_value=result):
            stop_unit('fixture.service')
        for value in ('', 'LoadState=loaded\nActiveState=active\n'):
            with patch('native_repository_fixture.subprocess.run', return_value=SimpleNamespace(stdout=value)):
                with self.assertRaises(AssertionError):
                    stop_unit('fixture.service')


class ControllerOutputTests(unittest.TestCase):
    def reader(self, source):
        process = subprocess.Popen([sys.executable, '-u', '-c', source], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        self.addCleanup(self.finish, process)
        output = io.BytesIO()
        reader = ControllerOutput(process, output)
        self.addCleanup(reader.close)
        return reader, output

    @staticmethod
    def finish(process):
        if process.poll() is None:
            process.kill()
        process.communicate(timeout=5)

    def test_ready_and_cleanup_in_one_write_keep_the_unread_next_receipt(self):
        raw = 'diagnostic\n{"ready":true}\n{"cleanup":"PASS"}\n'
        reader, output = self.reader('import sys; sys.stdout.write(' + repr(raw) + ')')
        self.assertEqual(reader.until('ready', 5), {'ready': True})
        self.assertEqual(reader.until('cleanup', 5), {'cleanup': 'PASS'})
        self.assertEqual(output.getvalue(), raw.encode())

    def test_stalled_controller_has_a_fixed_observation_deadline(self):
        reader, _ = self.reader('import time; time.sleep(5)')
        with self.assertRaises(TimeoutError):
            reader.line(time.monotonic() + .05)

    def test_controller_eof_cannot_be_reclassified_as_successful_cleanup(self):
        reader, _ = self.reader('print(' + repr('{"ready":true}') + ')')
        self.assertEqual(reader.until('ready', 5), {'ready': True})
        with self.assertRaisesRegex(RuntimeError, 'without cleanup'):
            reader.until('cleanup', 5)

    def test_cleanup_receipt_survives_failure_before_readiness(self):
        reader, _ = self.reader('import sys; print(' + repr('{"cleanup":"PASS"}') + '); sys.exit(1)')
        with self.assertRaisesRegex(RuntimeError, 'without ready'):
            reader.until('ready', 5)
        self.assertEqual(reader.until('cleanup', 5), {'cleanup': 'PASS'})

    def test_oversized_unterminated_line_is_bounded_and_retained(self):
        reader, output = self.reader('import sys; sys.stdout.write("x"*(1024*1024+1))')
        with self.assertRaisesRegex(ValueError, 'line bound'):
            reader.until('ready', 5)
        self.assertGreater(len(output.getvalue()), 1024 * 1024)
        self.assertLessEqual(len(output.getvalue()), 1024 * 1024 + 8192)


if __name__ == '__main__':
    unittest.main()
