import json
import os
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from worker_release import DeploymentError, PAYLOAD, SOURCES, WorkerRelease, read_bundle, sha, validate_config


REVISION = "a" * 40


def bundle(directory, value="new"):
    directory.mkdir()
    for name in SOURCES:
        (directory / name).write_text("VALUE = " + repr(value) + "\n")
    (directory / "requirements.txt").write_text("example==1.0\n")
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
        self.configuration = self.root / "config.json"
        self.private = json.dumps({"launcher": str(self.root / "launcher.py"),
                                   "supervisorUnit": "example.service", "private": "operator-$secret"}).encode()
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
                       "python": "/usr/bin/python3", "service": "example.service",
                       "healthUrl": "http://127.0.0.1:8080/actuator/health/executor",
                       "probeTokenFile": str(self.token), "probeCopyId": "00000000-0000-4000-8000-000000000001"}
        self.calls = []
        self.active = True
        self.fail_dependencies = False
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
        elif args[0] == "/usr/bin/python3":
            if self.fail_dependencies:
                raise DeploymentError("dependency mismatch")
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
            arguments = [self.config["python"], str(self.root / "worker.py"), "--config", str(self.configuration)]
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
        self.assertEqual((self.root / "launcher.py").stat().st_mode & 0o777, 0o644)
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
        (self.root / "worker.py").write_text("VALUE = 'drift'\n")
        with self.assertRaisesRegex(DeploymentError, "source changed"):
            self.worker.install(plan)
        self.assertFalse(any(call[:2] in (("systemctl", "start"), ("systemctl", "stop")) for call in self.calls))

    def test_bad_bundle_and_dependency_mismatch_never_stop_the_worker(self):
        (self.image / "worker.py").write_text("tampered\n")
        with self.assertRaisesRegex(DeploymentError, "checksum mismatch"):
            self.worker.prepare("image", REVISION)
        self.assertEqual(self.calls[-1][:2], ("docker", "rm"))
        checksums(self.image)
        self.fail_dependencies = True
        with self.assertRaisesRegex(DeploymentError, "pinned requirements"):
            self.worker.prepare("image", REVISION)
        self.assertFalse(any(call[:2] in (("systemctl", "start"), ("systemctl", "stop")) for call in self.calls))

    def test_bundle_refuses_private_config_extra_paths_links_and_different_revisions(self):
        with self.assertRaisesRegex(DeploymentError, "revision differs"):
            read_bundle(self.image, "b" * 40)
        extra = self.image / "config.json"
        extra.write_bytes(self.private)
        with self.assertRaisesRegex(DeploymentError, "only the complete runtime"):
            read_bundle(self.image, REVISION)
        extra.unlink()
        (self.image / "worker.py").unlink()
        (self.image / "worker.py").symlink_to(self.configuration)
        with self.assertRaisesRegex(DeploymentError, "invalid file"):
            read_bundle(self.image, REVISION)

    def test_tampered_release_and_unmanaged_links_are_never_accepted(self):
        plan = self.worker.prepare("image", REVISION)
        self.worker.install(plan)
        (self.worker.store / plan["release"] / "worker.py").write_text("VALUE='drift'\n")
        with self.assertRaisesRegex(DeploymentError, "checksum mismatch"):
            self.worker.verify(plan)
        (self.root / "launcher.py").unlink()
        (self.root / "launcher.py").symlink_to(self.configuration)
        with self.assertRaisesRegex(DeploymentError, "not managed"):
            self.worker.verify(plan)

    def test_worker_service_must_execute_the_configured_program(self):
        def command(*args):
            return "123" if "show" in args else ""
        self.worker.command = command
        with patch.object(Path, "read_bytes", return_value=b"python\0/unrelated.py\0"):
            with self.assertRaisesRegex(DeploymentError, "configured worker sources"):
                WorkerRelease._active(self.worker)
        arguments = os.fsencode(self.config["python"]) + b"\0" + os.fsencode(self.root / "worker.py") + b"\0--config\0" + os.fsencode(self.configuration) + b"\0"
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
