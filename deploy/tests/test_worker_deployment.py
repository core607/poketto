import json
import os
from pathlib import Path
import shutil
import struct
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from worker_release import BINARIES, DeploymentError, PAYLOAD, SOURCES, WorkerRelease, read_bundle, sha, validate_config


REVISION = "a" * 40


def program(directory, value):
    (directory / "bin").mkdir(mode=0o755)
    raw = bytearray(120)
    raw[:7] = b"\x7fELF\x02\x01\x01"
    struct.pack_into("<HH", raw, 16, 2, 62)
    struct.pack_into("<Q", raw, 32, 64)
    struct.pack_into("<HH", raw, 54, 56, 1)
    struct.pack_into("<I", raw, 64, 1)
    for name in BINARIES:
        (directory / name).write_bytes(bytes(raw) + value.encode())
        (directory / name).chmod(0o755)


def bundle(directory, value="new"):
    directory.mkdir()
    for name in SOURCES:
        (directory / name).write_text("VALUE = " + repr(value) + "\n")
    program(directory, value)
    (directory / "revision").write_text(REVISION + "\n")
    checksums(directory)


def checksums(directory):
    (directory / "SHA256SUMS").write_text("".join(
        sha((directory / name).read_bytes()) + "  " + name + "\n" for name in sorted(PAYLOAD)))


class WorkerDeploymentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.base = Path(temporary.name)
        self.root = self.base / "worker"
        self.root.mkdir()
        for name in SOURCES:
            (self.root / name).write_text("VALUE = 'old'\n")
        program(self.root, "old")
        self.configuration = self.root / "config.json"
        self.private = json.dumps({"supervisorUnit": "example.service", "private": "operator-$secret"}).encode()
        self.configuration.write_bytes(self.private)
        self.configuration.chmod(0o600)
        self.token = self.root / "probe-token"
        self.token.write_text("synthetic-probe-token")
        self.token.chmod(0o600)
        (self.root / "public.pem").write_text("retained public key")
        (self.root / "tools").mkdir()
        (self.root / "tools" / "sentinel").write_text("retained tools")
        self.image = self.base / "image"
        bundle(self.image)
        self.config = {"sourceDirectory": str(self.root), "configFile": str(self.configuration),
                       "service": "example.service",
                       "healthUrl": "http://127.0.0.1:8080/actuator/health/executor",
                       "probeTokenFile": str(self.token), "probeCopyId": "00000000-0000-4000-8000-000000000001"}
        self.calls = []
        self.active = True
        self.fail_start = False
        self.fail_contract = False
        self.worker = WorkerRelease(self.config, self.base, self.command)
        self.worker._active = self.assert_active

    def assert_active(self):
        if not self.active:
            raise DeploymentError("worker is not active")

    def command(self, *args):
        self.calls.append(args)
        if args[:2] == ("docker", "create"):
            return "c" * 64
        if args[:2] == ("docker", "cp"):
            shutil.copytree(self.image, args[-1], dirs_exist_ok=True)
        elif args[:2] == ("docker", "rm"):
            pass
        elif args[:2] == ("systemctl", "stop"):
            self.active = False
        elif args[:2] == ("systemctl", "start"):
            if self.fail_start:
                self.fail_start = False
                raise DeploymentError("startup failed")
            self.active = True
        elif args[:2] == ("systemctl", "show"):
            if self.fail_contract:
                return "{ argv[]=/unrelated/program ; }"
            arguments = [str(self.root / "poketto-supervisor"), "--config", str(self.configuration)]
            if "--property=ExecStopPost" in args:
                arguments.append("--cleanup")
            return "{ argv[]=" + " ".join(arguments) + " ; }"
        else:
            raise AssertionError(args)
        return ""

    def test_source_update_preserves_configuration_tools_and_recovers_without_another_restart(self):
        plan = self.worker.prepare("immutable-image-id", REVISION)
        self.assertFalse(self.worker.current.exists())
        self.assertFalse(any(call[:2] in (("systemctl", "start"), ("systemctl", "stop")) for call in self.calls))
        self.worker.install(plan)
        self.worker.verify(plan)
        for name in SOURCES:
            self.assertEqual((self.root / name).read_bytes(), (self.image / name).read_bytes())
            self.assertTrue((self.root / name).is_symlink())
            self.assertEqual((self.worker.store / plan["previousRelease"] / name).read_text(), "VALUE = 'old'\n")
        self.assertEqual(self.configuration.read_bytes(), self.private)
        self.assertEqual(self.configuration.stat().st_mode & 0o777, 0o600)
        self.assertEqual((self.root / "public.pem").read_text(), "retained public key")
        self.assertEqual((self.root / "tools" / "sentinel").read_text(), "retained tools")
        # Imports create bytecode beside the selected modules; it is not another source generation.
        cache = self.worker.store / plan["release"] / "__pycache__"
        cache.mkdir()
        (cache / "worker.cpython-312.pyc").write_bytes(b"synthetic cache")
        retry = self.worker.prepare("immutable-image-id", REVISION)
        self.worker.install(retry)
        self.assertEqual(sum(call[:2] == ("systemctl", "stop") for call in self.calls), 1)

    def test_an_interrupted_first_adoption_keeps_old_bytes_and_can_finish(self):
        plan = self.worker.prepare("image", REVISION)
        self.worker._select(plan["previousRelease"])
        for name in sorted(SOURCES)[:3]:
            path = self.root / name
            path.unlink()
            path.symlink_to("releases/current/" + name)
        self.assertTrue(all((self.root / name).read_text() == "VALUE = 'old'\n" for name in SOURCES))
        retry = self.worker.prepare("image", REVISION)
        self.worker.install(retry)
        self.worker.verify(retry)

    def test_native_generation_retains_obsolete_helpers_on_rollback(self):
        old = {name: (self.root / name).read_bytes() for name in SOURCES | BINARIES}
        old["cli.py"] = b"VALUE = 'previous Python CLI'\n"
        old["requirements.txt"] = b"example==1.0\n"
        previous = self.worker._stage(old)
        self.worker._select(previous)
        for name in self.worker._aliases(old):
            target = self.root / name
            target.unlink(missing_ok=True)
            target.symlink_to("releases/current/" + name)
        plan = self.worker.prepare("image", REVISION)
        self.assertEqual(previous, plan["previousRelease"])
        self.fail_start = True
        with self.assertRaisesRegex(DeploymentError, "previous sources restored"):
            self.worker.install(plan)
        self.assertEqual(previous, self.worker._selected())
        self.assertEqual(old["cli.py"], (self.root / "cli.py").read_bytes())
        self.assertTrue((self.worker.store / previous / "poketto-supervisor").is_file())
        self.worker.install(plan)
        self.worker.verify(plan)
        selected = (self.root / "poketto-supervisor").resolve().parent
        self.assertEqual((self.image / "bin/poketto").read_bytes(), (selected / "bin/poketto").read_bytes())
        self.assertFalse((selected / "cli.py").exists())
        self.assertEqual(0o555, (selected / "bin/poketto").stat().st_mode & 0o777)

    def test_program_integrity_and_execute_permissions_are_required(self):
        binary = self.image / "poketto-supervisor"
        original = binary.read_bytes()
        for mode in (0o644, 0o777):
            binary.chmod(mode)
            with self.assertRaisesRegex(DeploymentError, "root-owned, executable"):
                self.worker.prepare("image", REVISION)
        binary.chmod(0o755)
        for raw in (b"#!/bin/sh\nexit 0\n", original[:64], original[:64] + struct.pack("<I", 3) + original[68:]):
            binary.write_bytes(raw)
            checksums(self.image)
            with self.assertRaises(DeploymentError):
                self.worker.prepare("image", REVISION)
        self.assertFalse(any(call[:2] == ("systemctl", "stop") for call in self.calls))

    def test_native_generation_rollback_preserves_removed_helper_files(self):
        old = {name: (self.root / name).read_bytes() for name in SOURCES | BINARIES}
        old.update({"shell_loop.py": b"VALUE = 'previous Python driver'\n",
                    "bin/poketto": (self.root / "bin/poketto").read_bytes(),
                    "requirements.txt": b"example==1.0\n"})
        previous = self.worker._stage(old)
        self.worker._select(previous)
        for name in self.worker._aliases(old):
            target = self.root / name
            target.unlink(missing_ok=True)
            target.symlink_to("releases/current/" + name)
        plan = self.worker.prepare("image", REVISION)
        self.fail_start = True
        with self.assertRaisesRegex(DeploymentError, "previous sources restored"):
            self.worker.install(plan)
        selected = (self.root / "poketto-supervisor").resolve().parent
        self.assertEqual(old["shell_loop.py"], (selected / "shell_loop.py").read_bytes())
        self.assertEqual(old["bin/poketto"], (selected / "bin/poketto").read_bytes())
        self.assertEqual(old["bin/poketto-shell"], (selected / "bin/poketto-shell").read_bytes())
        self.worker.install(plan)
        self.worker.verify(plan)
        selected = (self.root / "poketto-supervisor").resolve().parent
        self.assertFalse((selected / "shell_loop.py").exists())
        self.assertEqual((self.image / "bin/poketto-shell").read_bytes(), (selected / "bin/poketto-shell").read_bytes())

    def test_failed_start_restores_the_whole_previous_release_and_same_plan_retries(self):
        plan = self.worker.prepare("image", REVISION)
        self.fail_start = True
        with self.assertRaisesRegex(DeploymentError, "previous sources restored"):
            self.worker.install(plan)
        self.assertTrue(self.active)
        self.assertTrue(all((self.root / name).read_text() == "VALUE = 'old'\n" for name in SOURCES))
        self.assertEqual(self.configuration.read_bytes(), self.private)
        self.worker.install(plan)
        self.worker.verify(plan)

    def test_failed_link_adoption_restarts_the_coherent_previous_sources(self):
        plan = self.worker.prepare("image", REVISION)
        replace = Path.replace
        installed = []

        def fail_after_two_links(path, target):
            if path.name.endswith(".release-next"):
                installed.append(path)
                if len(installed) == 3:
                    raise OSError("synthetic interrupted file installation")
            return replace(path, target)

        with patch.object(Path, "replace", new=fail_after_two_links):
            with self.assertRaisesRegex(DeploymentError, "previous sources restored"):
                self.worker.install(plan)
        self.assertTrue(self.active)
        self.assertTrue(all((self.root / name).read_text() == "VALUE = 'old'\n" for name in SOURCES))
        self.worker.install(plan)
        self.worker.verify(plan)

    def test_private_operator_umask_does_not_hide_public_sources_from_the_execution_account(self):
        previous = os.umask(0o077)
        try:
            plan = self.worker.prepare("image", REVISION)
            self.worker.install(plan)
        finally:
            os.umask(previous)
        for path in (self.worker.store, self.worker.store / plan["release"]):
            self.assertEqual(path.stat().st_mode & 0o777, 0o755)
        self.assertEqual((self.root / "resource_pool.py").stat().st_mode & 0o777, 0o644)
        self.assertEqual(self.configuration.stat().st_mode & 0o777, 0o600)

    def test_interruption_after_switch_restarts_a_stopped_worker_on_reconciliation(self):
        plan = self.worker.prepare("image", REVISION)
        self.worker.install(plan)
        self.active = False
        self.worker.install(plan)
        self.worker.verify(plan)

    def test_private_configuration_and_source_drift_refuse_install_before_stop(self):
        plan = self.worker.prepare("image", REVISION)
        self.configuration.write_text("changed private configuration")
        with self.assertRaisesRegex(DeploymentError, "configuration changed"):
            self.worker.install(plan)
        self.configuration.write_bytes(self.private)
        (self.root / "resource_pool.py").write_text("VALUE = 'drift'\n")
        with self.assertRaisesRegex(DeploymentError, "source changed"):
            self.worker.install(plan)
        self.assertFalse(any(call[:2] in (("systemctl", "start"), ("systemctl", "stop")) for call in self.calls))

    def test_bad_bundle_never_stops_the_worker(self):
        (self.image / "resource_pool.py").write_text("tampered\n")
        with self.assertRaisesRegex(DeploymentError, "checksum mismatch"):
            self.worker.prepare("image", REVISION)
        self.assertEqual(self.calls[-1][:2], ("docker", "rm"))
        self.assertFalse(any(call[:2] in (("systemctl", "start"), ("systemctl", "stop")) for call in self.calls))

    def test_bundle_refuses_private_config_extra_paths_links_and_different_revisions(self):
        with self.assertRaisesRegex(DeploymentError, "revision differs"):
            read_bundle(self.image, "b" * 40)
        extra = self.image / "config.json"
        extra.write_bytes(self.private)
        with self.assertRaisesRegex(DeploymentError, "only the complete runtime"):
            read_bundle(self.image, REVISION)
        extra.unlink()
        (self.image / "resource_pool.py").unlink()
        (self.image / "resource_pool.py").symlink_to(self.configuration)
        with self.assertRaisesRegex(DeploymentError, "invalid file"):
            read_bundle(self.image, REVISION)

    def test_tampered_release_and_unmanaged_links_are_never_accepted(self):
        plan = self.worker.prepare("image", REVISION)
        self.worker.install(plan)
        (self.worker.store / plan["release"] / "resource_pool.py").write_text("VALUE='drift'\n")
        with self.assertRaisesRegex(DeploymentError, "checksum mismatch"):
            self.worker.verify(plan)
        (self.root / "resource_pool.py").unlink()
        (self.root / "resource_pool.py").symlink_to(self.configuration)
        with self.assertRaisesRegex(DeploymentError, "not managed"):
            self.worker.verify(plan)

    def test_worker_service_must_execute_the_configured_program(self):
        def command(*args):
            return "123" if "show" in args else ""
        self.worker.command = command
        with patch.object(Path, "read_bytes", return_value=b"python\0/unrelated.py\0"):
            with self.assertRaisesRegex(DeploymentError, "configured worker sources"):
                WorkerRelease._active(self.worker)
        arguments = os.fsencode(self.root / "poketto-supervisor") + b"\0--config\0" + os.fsencode(self.configuration) + b"\0"
        with patch.object(Path, "read_bytes", return_value=arguments):
            WorkerRelease._active(self.worker)

    def test_wrong_service_commands_are_rejected_before_extracting_or_stopping(self):
        self.fail_contract = True
        with self.assertRaisesRegex(DeploymentError, "service commands"):
            self.worker.prepare("image", REVISION)
        self.assertEqual(len(self.calls), 1)

    def test_paths_and_service_settings_are_validated(self):
        for key, value in [("sourceDirectory", "/"), ("configFile", "/etc/../private.json"),
                           ("service", "bad.service; reboot"), ("healthUrl", "http://example.test/actuator/health/executor")]:
            with self.assertRaises(DeploymentError):
                validate_config({**self.config, key: value})
        with self.assertRaises(DeploymentError):
            validate_config({**self.config, "healthUrl": "http://127.0.0.1:65536/actuator/health/executor"})
        self.root.chmod(0o777)
        with self.assertRaisesRegex(DeploymentError, "root-owned"):
            WorkerRelease(self.config, self.base, self.command)

    def test_a_root_owned_source_directory_cannot_have_a_replaceable_parent(self):
        self.base.chmod(0o777)
        try:
            with self.assertRaisesRegex(DeploymentError, "ancestors must prevent replacement"):
                WorkerRelease(self.config, self.base, self.command)
        finally:
            self.base.chmod(0o700)

    def test_mcp_probe_uses_only_the_configured_copy_and_does_not_follow_redirects(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        answer = {"copyId": self.config["probeCopyId"], "exitCode": 0, "stdout": "poketto-deployment-ready"}
        envelope = {"id": "deployment-probe", "result": {"content": [{"type": "text", "text": json.dumps(answer)}]}}
        response.read.return_value = json.dumps(envelope).encode()
        opener = MagicMock()
        opener.open.return_value = response
        with patch("worker_release.urllib.request.build_opener", return_value=opener) as factory:
            self.worker.probe()
            request = opener.open.call_args.args[0]
            self.assertEqual(request.full_url, "http://127.0.0.1:8080/mcp")
            self.assertEqual(request.get_header("Authorization"), "Bearer synthetic-probe-token")
            self.assertEqual(json.loads(request.data)["params"]["arguments"]["expectedCopyId"], self.config["probeCopyId"])
            self.assertEqual(factory.call_args.args[0].proxies, {})
            self.assertIsNone(factory.call_args.args[1].redirect_request(None, None, 302, None, None, "http://example.test"))
            for failure in ({"error": "rejected"}, {"id": "deployment-probe", "result": {"isError": True}},
                            {"id": "deployment-probe", "result": {"content": []}}):
                response.read.return_value = json.dumps(failure).encode()
                with self.assertRaisesRegex(DeploymentError, "read-only MCP probe failed"):
                    self.worker.probe()

    def test_probe_credentials_are_private_and_missing_or_public_tokens_fail_before_changes(self):
        self.token.chmod(0o644)
        with self.assertRaisesRegex(DeploymentError, "private root-owned"):
            self.worker.prepare("image", REVISION)
        self.assertFalse(any(call[0] == "docker" for call in self.calls))


if __name__ == "__main__":
    unittest.main()
