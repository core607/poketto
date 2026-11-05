import json
import os
from pathlib import Path
import socket
import tempfile
import unittest
from unittest.mock import MagicMock, patch

from native_repository import DeploymentError, NativeRepository, consumer_socket, release_pin, sha


class NativeDeploymentTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.binary = self.root / "sandcrab"
        self.binary.write_bytes(b"test release")
        self.binary.chmod(0o755)
        config = self.root / "config.toml"
        config.write_bytes(b"test settings")
        token = self.root / "probe-token"
        token.write_text("test-token")
        token.chmod(0o600)
        self.endpoint = self.root / "control.sock"
        listener = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        self.addCleanup(listener.close)
        listener.bind(str(self.endpoint))
        listener.listen(16)
        self.endpoint.chmod(0o660)
        self.config = {"binary": str(self.binary), "configFile": str(config), "configSha256": sha(config.read_bytes()),
                       "socket": str(self.endpoint), "service": "example.service", "probeTokenFile": str(token),
                       "healthUrl": "http://127.0.0.1:8080/actuator/health/executor",
                       "probeCopyId": "00000000-0000-4000-8000-000000000001"}
        self.pin = f"version=v0.1.0\nbundleSha256={'1' * 64}\nsandcrabSha256={sha(self.binary.read_bytes())}\n"
        self.environment = {"POKETTO_EXECUTOR_SOCKET": str(self.endpoint)}
        self.mount = {"Type": "bind", "Source": str(self.root), "Destination": str(self.root), "RW": False}
        self.container = {"Id": "application", "State": {"Pid": os.getpid()}, "Mounts": [
            self.mount, {"Type": "volume", "Destination": "/data", "RW": True}]}
        for name in ("STAGING_DIRECTORY", "TRANSFERS_DIRECTORY", "COPIES_METADATA_ROOT"):
            self.environment["POKETTO_EXECUTOR_" + name] = "/data/" + name.lower()
        self.info = {"apiVersion": 1, "serverId": "11111111-2222-4333-8444-555555555555",
                     "capabilities": ["sessions", "templates", "files", "uploads", "calls"],
                     "profiles": {"repository": {"network": "none", "hostCalls": True}}}
        self.service = NativeRepository(self.config, self.command)

    def command(self, *args):
        if args[:2] == ("docker", "run"):
            self.assertEqual(args[-4:], ("candidate-image", "-c", "1025", "/opt/poketto/sandcrab-release.properties"))
            return self.pin
        if args == ("docker", "exec", "application", "id", "-u"):
            return "10001\n"
        self.assertEqual(args[:5], ("docker", "exec", "application", "curl", "--disable"))
        self.assertEqual(args[-3:], ("--unix-socket", str(self.endpoint), "http://localhost/v1/info"))
        return json.dumps(self.info)

    def inspect(self):
        return self.service.inspect("candidate-image", self.container, self.environment)

    def test_process_owns_socket_and_a_restarted_service_can_use_the_same_pins(self):
        with patch.object(self.service, "process", return_value=(os.getpid(), "start1")):
            expected = self.inspect()
        self.info["serverId"] = "22222222-2222-4333-8444-555555555555"
        with patch.object(self.service, "process", return_value=(os.getpid(), "start2")):
            self.assertEqual(self.inspect(), expected)
        with patch.object(self.service, "process", return_value=(os.getpid() + 1, "start2")):
            with self.assertRaisesRegex(DeploymentError, "socket peer"):
                self.inspect()
        with patch.object(self.service, "process", side_effect=[(os.getpid(), "start1"), (os.getpid(), "start2")]):
            with self.assertRaisesRegex(DeploymentError, "restarted"):
                self.inspect()

    def test_preflight_rejects_wrong_release_config_and_network_profile(self):
        with patch.object(self.service, "process", return_value=(os.getpid(), "start")):
            self.info["profiles"]["repository"]["network"] = "proxy"
            with self.assertRaisesRegex(DeploymentError, "offline repository profile"):
                self.inspect()
            Path(self.config["configFile"]).write_bytes(b"replaced")
            with self.assertRaisesRegex(DeploymentError, "checksum pin"):
                self.inspect()
            Path(self.config["configFile"]).write_bytes(b"test settings")
            self.binary.write_bytes(b"different release")
            with self.assertRaisesRegex(DeploymentError, "checksum pin"):
                self.inspect()
        for malformed in (self.pin + "version=v1.0.0\n", self.pin.replace("v0.1.0", "latest"), ""):
            with self.assertRaisesRegex(DeploymentError, "invalid Sandcrab"):
                release_pin(malformed)

    def test_separate_template_profile_requires_offline_git_without_host_calls(self):
        with patch.object(self.service, "process", return_value=(os.getpid(), "start")):
            self.assertNotIn("templateProfile", self.inspect())
            self.environment["POKETTO_EXECUTOR_TEMPLATE_PROFILE"] = "builder"
            with self.assertRaisesRegex(DeploymentError, "template preparation profile"):
                self.inspect()
            self.info["profiles"]["builder"] = {"network": "proxy", "hostCalls": False}
            with self.assertRaisesRegex(DeploymentError, "template preparation profile"):
                self.inspect()
            self.info["profiles"]["builder"]["network"] = "none"
            receipt = self.inspect()
            self.assertEqual((receipt["profile"], receipt["templateProfile"]), ("repository", "builder"))

    def test_consumer_requires_the_directory_bind_writable_state_and_no_legacy_key(self):
        def verify():
            return consumer_socket(self.container, self.environment, self.endpoint)
        self.assertEqual(verify(), str(self.endpoint))
        self.mount["RW"] = True
        with self.assertRaisesRegex(DeploymentError, "parent read-only"):
            verify()
        self.mount["RW"] = False
        self.container["Mounts"].append({"Type": "bind", "Destination": str(self.endpoint), "RW": False})
        with self.assertRaisesRegex(DeploymentError, "parent read-only"):
            verify()
        self.container["Mounts"].pop()
        self.container["Mounts"].append({"Type": "bind", "Destination": "/data/transfers_directory", "RW": False})
        with self.assertRaisesRegex(DeploymentError, "TRANSFERS_DIRECTORY"):
            verify()
        self.container["Mounts"].pop()
        self.environment["POKETTO_EXECUTOR_SIGNING_KEY"] = "/some/operator/key"
        with self.assertRaisesRegex(DeploymentError, "signed executor key"):
            verify()

    def test_mcp_probe_uses_only_the_configured_copy_and_does_not_follow_redirects(self):
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        answer = {"copyId": self.config["probeCopyId"], "exitCode": 0, "stdout": "poketto-deployment-ready"}
        envelope = {"id": "deployment-probe", "result": {"content": [{"type": "text", "text": json.dumps(answer)}]}}
        response.read.return_value = json.dumps(envelope).encode()
        opener = MagicMock()
        opener.open.return_value = response
        with patch("native_repository.urllib.request.build_opener", return_value=opener) as factory:
            self.service.probe()
            request = opener.open.call_args.args[0]
            self.assertEqual(request.full_url, "http://127.0.0.1:8080/mcp")
            self.assertEqual(request.get_header("Authorization"), "Bearer test-token")
            self.assertEqual(json.loads(request.data)["params"]["arguments"]["expectedCopyId"], self.config["probeCopyId"])
            self.assertEqual(factory.call_args.args[0].proxies, {})
            self.assertIsNone(factory.call_args.args[1].redirect_request(None, None, 302, None, None, "http://example.test"))
            for failure in ({"error": "rejected"}, {"id": "deployment-probe", "result": {"isError": True}},
                            {"id": "deployment-probe", "result": {"content": []}}):
                response.read.return_value = json.dumps(failure).encode()
                with self.assertRaisesRegex(DeploymentError, "read-only MCP probe failed"):
                    self.service.probe()

    def test_private_probe_credentials_are_checked_before_inspecting_images(self):
        token = Path(self.config['probeTokenFile'])
        with patch.object(self.service, 'command') as command:
            token.chmod(0o644)
            with self.assertRaisesRegex(DeploymentError, 'private root-owned'):
                self.inspect()
            token.unlink()
            with self.assertRaises(FileNotFoundError):
                self.inspect()
            command.assert_not_called()

    def test_probe_paths_refuse_symlinks_and_replaceable_parents(self):
        token = Path(self.config['probeTokenFile'])
        alias = self.root / 'token-alias'
        alias.symlink_to(token.name)
        with patch.object(self.service, 'command') as command:
            self.config['probeTokenFile'] = str(alias)
            with self.assertRaisesRegex(DeploymentError, 'canonical'):
                self.inspect()
            self.config['probeTokenFile'] = str(token)
            self.root.chmod(0o777)
            with self.assertRaisesRegex(DeploymentError, 'ancestors must prevent replacement'):
                self.inspect()
            command.assert_not_called()
