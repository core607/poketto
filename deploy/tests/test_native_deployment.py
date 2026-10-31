import json
import os
from pathlib import Path
import socket
import sys
import tempfile
import unittest
from unittest.mock import MagicMock, patch

import native_service
from native_service import DeploymentError, NativeService, bounded_file, parse_deployment_state, sha, validate_config


class NativeDeploymentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.binary = self.root / "sandcrab"
        self.configuration = self.root / "config.toml"
        self.checker = self.root / "resource_pool.py"
        self.token = self.root / "token"
        for path, content, mode in (
                (self.binary, b"synthetic native program", 0o755),
                (self.configuration, b"[service]\n# synthetic protected configuration\n", 0o600),
                (self.checker, b"# synthetic resource pool checker\n", 0o644),
                (self.token, b"synthetic-not-a-real-token\n", 0o600)):
            path.write_bytes(content)
            path.chmod(mode)
        self.endpoint = self.root / "control.sock"
        self.listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(self.listener.close)
        self.listener.bind(str(self.endpoint))
        self.endpoint.chmod(0o660)
        self.listener.listen(8)
        self.config = {"binary": str(self.binary), "binarySha256": sha(self.binary.read_bytes()),
                       "configFile": str(self.configuration), "configSha256": sha(self.configuration.read_bytes()),
                       "poolChecker": str(self.checker), "poolCheckerSha256": sha(self.checker.read_bytes()),
                       "service": "sandcrab-fixture.service", "socket": str(self.endpoint),
                       "healthUrl": "http://127.0.0.1:8080/actuator/health/executor",
                       "probeTokenFile": str(self.token), "probeCopyId": "00000000-0000-4000-8000-000000000001"}
        self.command = MagicMock(return_value="")
        self.native = NativeService(self.config, self.command)
        self.process = {"pid": os.getpid(), "startTicks": "100"}

    def test_inspection_checks_pinned_files_real_socket_peer_and_only_read_only_host_command(self):
        with patch.object(self.native, "_process", return_value=self.process):
            plan = self.native.inspect()
        self.assertEqual(plan["kind"], "sandcrab-native-v1")
        self.assertEqual(plan["installedConfigSha256"], self.config["configSha256"])
        self.assertNotIn("pid", plan)
        self.command.assert_called_once_with(sys.executable, str(self.checker), "--service", self.config["service"])

    def test_same_pins_accept_a_new_observation_but_a_mid_observation_restart_fails(self):
        with patch.object(self.native, "_process", return_value=self.process):
            plan = self.native.inspect()
        replacement = {**self.process, "startTicks": "200"}
        with patch.object(self.native, "_process", return_value=replacement):
            self.native.verify(plan)
        with patch.object(self.native, "_process", side_effect=[self.process, replacement]):
            with self.assertRaisesRegex(DeploymentError, "restarted"):
                self.native.inspect()

    def test_wrong_socket_peer_is_rejected_before_the_pool_checker(self):
        with patch.object(self.native, "_process", return_value={"pid": os.getpid() + 1, "startTicks": "100"}):
            with self.assertRaisesRegex(DeploymentError, "socket peer"):
                self.native.inspect()
        self.command.assert_not_called()

    def test_changed_binary_configuration_or_checker_never_reaches_host_commands(self):
        for path in (self.binary, self.configuration, self.checker):
            original = path.read_bytes()
            with self.subTest(path=path.name):
                path.write_bytes(original + b"changed")
                with self.assertRaisesRegex(DeploymentError, "checksum pin"):
                    self.native.inspect()
                self.command.assert_not_called()
                path.write_bytes(original)

    def test_private_pins_refuse_aliases_extra_links_and_public_permissions(self):
        self.configuration.chmod(0o640)
        with self.assertRaisesRegex(DeploymentError, "owner-only"):
            self.native.inspect()
        self.configuration.chmod(0o600)
        alias = self.root / "alias.toml"
        os.link(self.configuration, alias)
        with self.assertRaisesRegex(DeploymentError, "one link"):
            self.native.inspect()
        alias.unlink()
        alias.symlink_to(self.configuration)
        with self.assertRaisesRegex(DeploymentError, "symlinks"):
            bounded_file(alias, 4096, private=True)
        self.command.assert_not_called()

    def test_real_process_reader_rejects_running_bytes_different_from_the_disk_pin(self):
        proc = self.root / "proc"
        process = proc / "123"
        process.mkdir(parents=True)
        (process / "stat").write_text("123 (native fixture) " + " ".join(["S"] + ["0"] * 18 + ["100"]))
        arguments = [str(self.binary), "serve", "--config", str(self.configuration)]
        (process / "cmdline").write_bytes(b"\0".join(os.fsencode(value) for value in arguments) + b"\0")
        (process / "exe").symlink_to(self.binary)
        self.command.return_value = "123\n"
        path_type = Path
        def paths(value):
            return proc if value == "/proc" else path_type(value)
        with patch.object(native_service, "Path", side_effect=paths):
            self.assertEqual(self.native._process(), {"pid": 123, "startTicks": "100"})
            (process / "exe").unlink()
            (process / "exe").write_bytes(b"old running bytes")
            with patch.object(native_service.os, "readlink", return_value=str(self.binary)):
                with self.assertRaisesRegex(DeploymentError, "running native executable differs"):
                    self.native._process()

    def test_legacy_or_malformed_configuration_is_not_interpreted_as_a_native_pin(self):
        for change in ({"sourceDirectory": "/opt/legacy"}, {"binarySha256": "unknown"},
                       {"binary": "/opt/../binary"}, {"service": "--user"},
                       {"healthUrl": "https://example.invalid/health"}):
            with self.subTest(change=next(iter(change))):
                with self.assertRaises(DeploymentError):
                    validate_config({**self.config, **change})

    def test_state_reader_preserves_status_and_rejects_mixed_or_unknown_schema(self):
        base = {"status": "pending", "revision": "a" * 40, "configuration": "b" * 64,
                "imageIds": {"app": "app-image-id", "frontend": "frontend-image-id"}}
        self.assertEqual(parse_deployment_state(json.dumps(base).encode()), base)
        native = {**base, "schemaVersion": 2, "nativeDependency": {"kind": "sandcrab-native-v1"}}
        self.assertEqual(parse_deployment_state(json.dumps(native).encode()), native)
        for change in ({"schemaVersion": 2.0}, {"schemaVersion": 3}, {"worker": None},
                       {"nativeDependency": {"kind": "legacy"}}, {"configuration": "unknown"}):
            with self.subTest(change=change):
                with self.assertRaises(DeploymentError):
                    parse_deployment_state(json.dumps({**native, **change}).encode())

    def test_probe_is_bounded_uses_only_the_named_copy_and_refuses_redirects(self):
        answer = {"copyId": self.config["probeCopyId"], "exitCode": 0, "stdout": "poketto-deployment-ready"}
        envelope = {"id": "deployment-probe", "result": {"content": [{"type": "text", "text": json.dumps(answer)}]}}
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        response.read.return_value = json.dumps(envelope).encode()
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(native_service.urllib.request, "build_opener", return_value=opener) as build:
            self.native.probe()
            request = opener.open.call_args.args[0]
            args = json.loads(request.data)["params"]["arguments"]
            self.assertEqual(args["expectedCopyId"], self.config["probeCopyId"])
            self.assertEqual(args["command"], "poketto --help >/dev/null && printf poketto-deployment-ready")
            self.assertEqual(opener.open.call_args.kwargs, {"timeout": 75})
            self.assertIsInstance(build.call_args.args[1], native_service.NoRedirects)
            response.read.return_value = b"x" * (1024 * 1024 + 1)
            with self.assertRaisesRegex(DeploymentError, "read-only MCP probe failed"):
                self.native.probe()
        self.command.assert_not_called()
