import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("updater", Path(__file__).parents[1] / "update-existing.py")
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)
REVISION = "a" * 40


class Docker:
    def __init__(self):
        self.calls = []
        self.fail_up = False
        self.changed_runtime = False
        self.changed_revision_environment = False
        self.image_revision = REVISION
        self.configuration = {
            "name": "example", "services": {
                "app": {"image": "old-app", "environment": {"REPOSITORY_PASSWORD": "retained-secret"}},
                "frontend": {"image": "old-frontend", "environment": {"API_BASE": "http://app:8080"}},
                "db": {"image": "retained-db"}, "gateway": {"image": "retained-gateway"}},
        }
        self.running = {}
        for name in ("app", "frontend", "db", "gateway"):
            env = self.configuration["services"][name].get("environment", {})
            self.running[name] = {
                "Id": name + "-original", "Image": "id-old-" + name,
                "Config": {"Image": "old-" + name, "Env": [k + "=" + v for k, v in env.items()],
                           "Labels": {"com.docker.compose.service": name}, "User": "10001"},
                "State": {"Health": {"Status": "healthy"}},
                "HostConfig": {"Memory": 123, "NetworkMode": "example_default", "CapDrop": ["ALL"]},
                "Mounts": [{"Type": "bind", "Source": "/data", "Destination": "/app/data", "RW": True}],
            }

    def __call__(self, *args):
        self.calls.append(args)
        if args[1:3] == ("image", "inspect"):
            return json.dumps([{"Id": "id-" + args[3], "Config": {"Labels": {
                "org.opencontainers.image.revision": self.image_revision}}}])
        if args[1] == "ps":
            return " ".join(item["Id"] for item in self.running.values())
        if args[1] == "inspect":
            return json.dumps(list(self.running.values()))
        if args[1] == "compose":
            config = copy.deepcopy(self.configuration)
            files = [Path(args[i + 1]) for i, value in enumerate(args) if value == "-f"]
            for file in files:
                if file.suffix == ".json":
                    for name, value in json.loads(file.read_text())["services"].items():
                        config["services"][name].update(value)
            if "config" in args:
                return json.dumps(config)
            if "up" in args:
                if self.fail_up:
                    raise updater.DeploymentError("simulated unavailable deployment")
                for name in ("app", "frontend"):
                    self.running[name]["Id"] = name + "-updated"
                    image = config["services"][name]["image"]
                    self.running[name]["Image"] = "id-" + image
                    self.running[name]["Config"]["Image"] = image
                if self.changed_runtime:
                    self.running["app"]["HostConfig"]["Memory"] += 1
                if self.changed_revision_environment:
                    self.running["app"]["Config"]["Env"].append("POKETTO_REVISION=unexpected")
                return ""
        raise AssertionError(args)


class ExistingDeploymentTests(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        (self.root / "compose.yaml").write_text("operator-owned compose\n")
        self.config = {"project": "example", "composeFiles": ["compose.yaml"]}
        self.docker = Docker()
        self.installation = updater.Installation(self.root, self.config, self.docker)

    def test_updates_only_images_and_leaves_operator_configuration_untouched(self):
        before = (self.root / "compose.yaml").read_bytes()
        result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["status"], "healthy")
        self.assertEqual((self.root / "compose.yaml").read_bytes(), before)
        self.assertEqual(self.docker.running["db"]["Id"], "db-original")
        self.assertEqual(self.docker.running["gateway"]["Id"], "gateway-original")
        self.assertIn("REPOSITORY_PASSWORD=retained-secret", self.docker.running["app"]["Config"]["Env"])
        up = next(call for call in self.docker.calls if "up" in call)
        self.assertIn("--no-deps", up)
        self.assertEqual(up[-2:], ("app", "frontend"))
        self.assertNotIn("--remove-orphans", up)
        state = self.installation.state_file.read_text()
        self.assertNotIn("retained-secret", state)
        self.assertEqual(json.loads(state)["status"], "healthy")

    def test_preflight_does_not_change_containers_or_confirm_a_deployment(self):
        self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend", True)["status"], "validated")
        self.assertFalse(self.installation.overlay.exists())
        self.assertFalse(self.installation.state_file.exists())
        self.assertFalse(any("up" in call for call in self.docker.calls))

    def test_failed_update_keeps_pending_state_and_same_target_can_be_reconciled(self):
        self.docker.fail_up = True
        with self.assertRaises(updater.DeploymentError):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")
        self.docker.fail_up = False
        self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend")["status"], "healthy")

    def test_pending_attempt_refuses_different_images(self):
        self.docker.fail_up = True
        with self.assertRaises(updater.DeploymentError):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.docker.calls.clear()
        with self.assertRaisesRegex(updater.DeploymentError, "unfinished deployment"):
            self.installation.update(REVISION, "other-app", "new-frontend")
        self.assertFalse(any("up" in call for call in self.docker.calls))

    def test_stale_declared_credentials_fail_before_container_changes(self):
        self.docker.configuration["services"]["app"]["environment"]["REPOSITORY_PASSWORD"] = "stale-secret"
        with self.assertRaisesRegex(updater.DeploymentError, "declared environment"):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertFalse(self.installation.state_file.exists())
        self.assertFalse(any("up" in call for call in self.docker.calls))

    def test_revision_mismatch_fails_before_container_changes(self):
        self.docker.image_revision = "b" * 40
        with self.assertRaisesRegex(updater.DeploymentError, "revision"):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertFalse(self.installation.state_file.exists())

    def test_runtime_drift_never_records_success(self):
        self.docker.changed_runtime = True
        with self.assertRaisesRegex(updater.DeploymentError, "runtime configuration"):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")

    def test_root_owned_configuration_rejects_path_escape_and_writable_files(self):
        path = self.root / "deployment.json"
        updater.write_json(path, self.config)
        self.assertEqual(updater.load_config(self.root), self.config)
        path.chmod(0o666)
        with self.assertRaisesRegex(updater.DeploymentError, "root-owned"):
            updater.load_config(self.root)
        updater.write_json(path, {**self.config, "composeFiles": ["../outside.yaml"]})
        with self.assertRaisesRegex(updater.DeploymentError, "below the deployment root"):
            updater.load_config(self.root)

    def test_revision_environment_is_protected_like_other_declared_settings(self):
        self.docker.changed_revision_environment = True
        with self.assertRaisesRegex(updater.DeploymentError, "runtime configuration"):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")

    def test_health_ports_are_validated_before_an_update(self):
        path = self.root / "deployment.json"
        for port in [0, 65536, 99999]:
            updater.write_json(path, {**self.config, "healthChecks": [{"url": "http://127.0.0.1:" + str(port) + "/health"}]})
            with self.assertRaisesRegex(updater.DeploymentError, "port"):
                updater.load_config(self.root)
        updater.write_json(path, {**self.config, "healthChecks": [{"url": "http://127.0.0.1:65535/health"}]})
        self.assertEqual(updater.load_config(self.root)["healthChecks"][0]["url"], "http://127.0.0.1:65535/health")

    def test_non_object_health_responses_leave_a_reconcilable_pending_attempt(self):
        self.config["healthChecks"] = [{"url": "http://127.0.0.1:8080/health", "status": "UP"}]
        response = MagicMock()
        response.__enter__.return_value = response
        response.status = 200
        with patch.object(updater.urllib.request, "urlopen", return_value=response):
            for payload in [[{"status": "UP"}], None, 1, "UP", {"status": "DOWN"}]:
                response.read.return_value = json.dumps(payload).encode()
                with self.assertRaisesRegex(updater.DeploymentError, "health check 1 did not confirm readiness"):
                    self.installation.update(REVISION, "new-app", "new-frontend")
                self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")
            response.read.return_value = b'{"status":"UP"}'
            self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend")["status"], "healthy")


if __name__ == "__main__":
    unittest.main()
