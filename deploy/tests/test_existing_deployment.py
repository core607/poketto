import copy
import importlib.util
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest.mock import MagicMock, patch

spec = importlib.util.spec_from_file_location("updater", Path(__file__).parents[1] / "update-existing.py")
updater = importlib.util.module_from_spec(spec)
spec.loader.exec_module(updater)
REVISION = "a" * 40
SOURCE = "https://github.com/example/poketto"


class Docker:
    def __init__(self):
        self.calls = []
        self.fail_up = False
        self.fail_listing = False
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
        # Local images: the fixture services' own, a stale build of this repository with two digest
        # references, an untagged one without any reference, one used by a container outside the
        # project and one from another repository.
        self.images = {}
        for reference in ("old-app", "old-frontend", "new-app", "new-frontend"):
            self.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        for reference in ("other-app", "retained-db", "retained-gateway"):
            self.images["id-" + reference] = {"refs": [reference], "source": None}
        self.images["id-stale"] = {"refs": ["registry/poketto@sha256:stale-a", "registry/poketto@sha256:stale-b"], "source": SOURCE}
        self.images["id-untagged"] = {"refs": [], "source": SOURCE}
        self.images["id-sidecar"] = {"refs": ["registry/poketto@sha256:sidecar"], "source": SOURCE}
        self.images["id-foreign"] = {"refs": ["registry/other@sha256:1"], "source": "https://github.com/example/other"}
        self.sidecar = {"Id": "sidecar-container", "Image": "id-sidecar",
                        "Config": {"Image": "registry/poketto@sha256:sidecar", "Labels": {}}}
        self.removed = []

    def find_image(self, key):
        for image_id, image in self.images.items():
            if key == image_id or key in image["refs"]:
                return image_id, image
        raise updater.DeploymentError("deployment command failed: docker")

    def __call__(self, *args):
        self.calls.append(args)
        if args[1:3] == ("image", "inspect"):
            result = []
            for key in args[3:]:
                image_id, image = self.find_image(key)
                labels = {"org.opencontainers.image.revision": self.image_revision}
                if image["source"]:
                    labels["org.opencontainers.image.source"] = image["source"]
                # Like Docker, answer null rather than an empty list when there is nothing to list.
                result.append({"Id": image_id, "RepoTags": None, "RepoDigests": list(image["refs"]) or None,
                               "Config": {"Labels": labels}})
            return json.dumps(result)
        if args[1:3] == ("image", "rm"):
            image_id, _ = self.find_image(args[3])
            self.removed.append(args[3])
            # Docker deletes an untagged image at its first digest reference.
            del self.images[image_id]
            return ""
        if args[1] == "images":
            if self.fail_listing:
                raise subprocess.TimeoutExpired("docker", 240)
            wanted = args[args.index("--filter") + 1].split("=", 2)[2]
            return "\n".join(image_id for image_id, image in self.images.items() if image["source"] == wanted)
        if args[1] == "ps":
            ids = [item["Id"] for item in self.running.values()]
            if "--filter" not in args:
                ids.append(self.sidecar["Id"])
            return " ".join(ids)
        if args[1] == "inspect":
            records = list(self.running.values()) + [self.sidecar]
            if args[2] == "--format":
                return "\n".join(record["Image"] for record in records if record["Id"] in args[4:])
            return json.dumps([record for record in records if record["Id"] in args[2:]])
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

    def test_healthy_update_retires_unreferenced_images_of_this_repository(self):
        result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["retiredImages"], 2)
        self.assertNotIn("id-stale", self.docker.images)
        self.assertNotIn("id-untagged", self.docker.images)
        for retained in ("id-new-app", "id-new-frontend", "id-old-app", "id-old-frontend",
                         "id-retained-db", "id-retained-gateway", "id-sidecar", "id-foreign"):
            self.assertIn(retained, self.docker.images)
        self.assertEqual(self.docker.removed, ["registry/poketto@sha256:stale-a", "id-untagged"])
        self.assertFalse(any("--force" in call or "-f" in call for call in self.docker.calls if call[1:3] == ("image", "rm")))
        healthy = self.docker.calls.index(next(call for call in self.docker.calls if call[1] == "images"))
        self.assertLess(self.docker.calls.index(next(call for call in self.docker.calls if "up" in call)), healthy)

    def test_retirement_needs_the_source_label_and_never_fails_a_healthy_deployment(self):
        self.docker.images["id-new-app"]["source"] = None
        self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend")["retiredImages"], 0)
        self.assertIn("id-stale", self.docker.images)
        self.assertIn("id-untagged", self.docker.images)
        self.docker.images["id-new-app"]["source"] = SOURCE
        self.docker.fail_listing = True
        result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["status"], "healthy")
        self.assertIsNone(result["retiredImages"])
        self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "healthy")

    def test_preflight_does_not_change_containers_or_confirm_a_deployment(self):
        self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend", True)["status"], "validated")
        self.assertFalse(self.installation.overlay.exists())
        self.assertFalse(self.installation.state_file.exists())
        self.assertFalse(any("up" in call for call in self.docker.calls))
        self.assertEqual(self.docker.removed, [])

    def test_failed_update_keeps_pending_state_and_same_target_can_be_reconciled(self):
        self.docker.fail_up = True
        with self.assertRaises(updater.DeploymentError):
            self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")
        self.assertEqual(self.docker.removed, [])
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
        opener = MagicMock()
        opener.open.return_value = response
        with patch.object(updater.urllib.request, "build_opener", return_value=opener):
            for payload in [[{"status": "UP"}], None, 1, "UP", {"status": "DOWN"}]:
                response.read.return_value = json.dumps(payload).encode()
                with self.assertRaisesRegex(updater.DeploymentError, "health check 1 did not confirm readiness"):
                    self.installation.update(REVISION, "new-app", "new-frontend")
                self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")
            response.read.return_value = b'{"status":"UP"}'
            self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend")["status"], "healthy")

    def test_health_redirects_are_not_followed(self):
        visited = []

        class HealthHandler(BaseHTTPRequestHandler):
            def do_GET(self):
                visited.append(self.path)
                if self.path == "/health":
                    self.send_response(302)
                    self.send_header("Location", "/target")
                    self.end_headers()
                else:
                    self.send_response(200)
                    self.end_headers()
                    self.wfile.write(b'{"status":"UP"}')

            def log_message(self, *args):
                pass

        server = ThreadingHTTPServer(("127.0.0.1", 0), HealthHandler)
        thread = threading.Thread(target=lambda: server.serve_forever(poll_interval=0.01), daemon=True)
        thread.start()
        try:
            self.config["healthChecks"] = [{"url": "http://127.0.0.1:" + str(server.server_port) + "/health", "status": "UP"}]
            with self.assertRaisesRegex(updater.DeploymentError, "health check 1 is unavailable"):
                self.installation.update(REVISION, "new-app", "new-frontend")
            self.assertEqual(visited, ["/health"])
            self.assertEqual(json.loads(self.installation.state_file.read_text())["status"], "pending")
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=5)

    def test_health_configuration_requires_a_list_of_objects_with_string_urls(self):
        path = self.root / "deployment.json"
        for checks in [None, 1, {}, [1], [None], ["http://127.0.0.1:8080/"]]:
            updater.write_json(path, {**self.config, "healthChecks": checks})
            with self.assertRaisesRegex(updater.DeploymentError, "list of objects"):
                updater.load_config(self.root)
        updater.write_json(path, {**self.config, "healthChecks": [{"url": None}]})
        with self.assertRaisesRegex(updater.DeploymentError, "loopback HTTP entrance"):
            updater.load_config(self.root)


if __name__ == "__main__":
    unittest.main()
