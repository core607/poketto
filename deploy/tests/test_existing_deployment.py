import copy
import importlib.util
import io
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import subprocess
import tempfile
import threading
import unittest
from unittest import mock
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
        self.fail_containers = False
        self.vanished_containers = set()
        self.fail_inspect = set()
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
        if key in self.fail_inspect:
            raise updater.DeploymentError("deployment command failed: docker")
        for image_id, image in self.images.items():
            if key == image_id or key in image["refs"]:
                return image_id, image
        raise updater.DeploymentError("deployment command failed: docker", missing=True)

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
        if args[1] == "ps":
            if self.fail_containers and "--filter" not in args:
                raise subprocess.TimeoutExpired("docker", 240)
            ids = [item["Id"] for item in self.running.values()]
            if "--filter" not in args:
                ids.append(self.sidecar["Id"])
            return " ".join(ids)
        if args[1] == "inspect":
            records = list(self.running.values()) + [self.sidecar]
            if args[2] == "--format":
                if set(args[4:]) & self.vanished_containers:
                    raise updater.DeploymentError("deployment command failed: docker", missing=True)
                return "\n".join(record["Image"] for record in records if record["Id"] in args[4:])
            return json.dumps([record for record in records if record["Id"] in args[2:]])
        if args[1] == "compose":
            config = copy.deepcopy(self.configuration)
            files = [Path(args[i + 1]) for i, value in enumerate(args) if value == "-f"]
            for file in files:
                if file.suffix == ".json":
                    for name, value in json.loads(file.read_text())["services"].items():
                        overlay = copy.deepcopy(value)
                        if "environment" in overlay:
                            environment = config["services"][name].setdefault("environment", {})
                            environment.update({key: item.replace("$$", "$") for key, item in overlay.pop("environment").items()})
                        config["services"][name].update(overlay)
            if "config" in args:
                for service in config["services"].values():
                    if "environment" in service:
                        service["environment"] = {key: value.replace("$", "$$") for key, value in service["environment"].items()}
                return json.dumps(config)
            if "up" in args:
                if self.fail_up:
                    raise updater.DeploymentError("simulated unavailable deployment")
                for name in ("app", "frontend"):
                    self.running[name]["Id"] = name + "-updated"
                    image = config["services"][name]["image"]
                    self.running[name]["Image"] = self.find_image(image)[0]
                    self.running[name]["Config"]["Image"] = image
                    self.running[name]["Config"]["Env"] = [key + "=" + value for key, value in config["services"][name].get("environment", {}).items()]
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

    def test_identity_settings_are_literal_private_and_retained_on_image_only_updates(self):
        secret = "synthetic-$literal-${VAR}-'quoted'=value"
        settings = updater.read_settings(io.StringIO(
            "POKETTO_RESEND_API_KEY=" + secret + "\nPOKETTO_EMAIL_FROM=Example <noreply@example.test>\n"
            "POKETTO_GOOGLE_CLIENT_ID=client\nPOKETTO_GOOGLE_CLIENT_SECRET=" + secret + "\n"))
        result = self.installation.update(REVISION, "new-app", "new-frontend", settings=settings)
        self.assertEqual(result["status"], "healthy")
        expected = [key + "=" + value for key, value in settings.items()]
        for value in expected + ["REPOSITORY_PASSWORD=retained-secret"]:
            self.assertIn(value, self.docker.running["app"]["Config"]["Env"])
        self.assertEqual(self.docker.running["frontend"]["Config"]["Env"], ["API_BASE=http://app:8080"])
        self.assertNotIn(secret, self.installation.state_file.read_text() + json.dumps(result) + str(self.docker.calls))
        self.assertEqual(self.installation.overlay.stat().st_mode & 0o777, 0o600)
        self.installation.update(REVISION, "new-app", "new-frontend")
        for value in expected:
            self.assertIn(value, self.docker.running["app"]["Config"]["Env"])
        self.installation.update(REVISION, "new-app", "new-frontend", settings={
            "POKETTO_GOOGLE_CLIENT_ID": "", "POKETTO_GOOGLE_CLIENT_SECRET": ""})
        self.assertIn("POKETTO_GOOGLE_CLIENT_SECRET=", self.docker.running["app"]["Config"]["Env"])

    def github_settings(self):
        return {
            "POKETTO_GITHUB_APP_ID": "12345",
            "POKETTO_GITHUB_CLIENT_ID": "Iv1.synthetic",
            "POKETTO_GITHUB_CLIENT_SECRET": "synthetic-client-secret",
            "POKETTO_GITHUB_PRIVATE_KEY": "c3ludGhldGlj",
            "POKETTO_GITHUB_WEBHOOK_SECRET": "synthetic-webhook-$literal-'quotes'",
        }

    def configure_repository_key(self):
        key = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="
        self.docker.configuration["services"]["app"]["environment"]["POKETTO_REPOSITORY_CREDENTIAL_KEY"] = key
        self.docker.running["app"]["Config"]["Env"].append("POKETTO_REPOSITORY_CREDENTIAL_KEY=" + key)

    def test_github_settings_stay_private_and_support_retention_webhook_only_and_clearing(self):
        self.configure_repository_key()
        settings = self.github_settings()
        parsed = updater.read_settings(io.StringIO("\n".join(key + "=" + value for key, value in settings.items())))
        result = self.installation.update(REVISION, "new-app", "new-frontend", settings=parsed)
        self.installation.update(REVISION, "new-app", "new-frontend")
        for key, value in settings.items():
            self.assertIn(key + "=" + value, self.docker.running["app"]["Config"]["Env"])
        public_output = self.installation.state_file.read_text() + json.dumps(result) + str(self.docker.calls)
        for key in ("CLIENT_SECRET", "PRIVATE_KEY", "WEBHOOK_SECRET"):
            self.assertNotIn(settings["POKETTO_GITHUB_" + key], public_output)
        self.assertEqual(self.installation.overlay.stat().st_mode & 0o777, 0o600)
        self.assertFalse(any("GITHUB" in value for value in self.docker.running["frontend"]["Config"]["Env"]))
        self.installation.update(REVISION, "new-app", "new-frontend", settings={
            "POKETTO_GITHUB_CLIENT_SECRET": "", "POKETTO_GITHUB_PRIVATE_KEY": ""})
        self.assertIn("POKETTO_GITHUB_WEBHOOK_SECRET=" + settings["POKETTO_GITHUB_WEBHOOK_SECRET"],
                      self.docker.running["app"]["Config"]["Env"])
        self.installation.update(REVISION, "new-app", "new-frontend", settings={key: "" for key in settings})
        for key in settings:
            self.assertIn(key + "=", self.docker.running["app"]["Config"]["Env"])
        self.assertEqual((self.root / "compose.yaml").read_text(), "operator-owned compose\n")

    def test_partial_github_settings_never_restart_containers(self):
        self.configure_repository_key()
        for missing in self.github_settings():
            settings = self.github_settings()
            del settings[missing]
            with self.subTest(missing=missing), self.assertRaises(updater.DeploymentError):
                self.installation.update(REVISION, "new-app", "new-frontend", settings=settings)
        for key, value in (("APP_ID", "0"), ("CLIENT_ID", "invalid client"), ("WEBHOOK_SECRET", "short"),
                           ("PRIVATE_KEY", "-----BEGIN PRIVATE KEY-----")):
            settings = self.github_settings()
            settings["POKETTO_GITHUB_" + key] = value
            with self.subTest(invalid=key), self.assertRaises(updater.DeploymentError):
                self.installation.update(REVISION, "new-app", "new-frontend", settings=settings)
        self.assertFalse(any("up" in call for call in self.docker.calls))

    def test_github_signing_requires_the_existing_host_encryption_key(self):
        with self.assertRaisesRegex(updater.DeploymentError, "POKETTO_REPOSITORY_CREDENTIAL_KEY"):
            self.installation.update(REVISION, "new-app", "new-frontend", settings=self.github_settings())
        self.assertFalse(any("up" in call for call in self.docker.calls))

    def test_pending_identity_update_reconciles_only_its_original_configuration(self):
        settings = {"POKETTO_EMAIL_DAILY_LIMIT": "80"}
        self.docker.fail_up = True
        with self.assertRaises(updater.DeploymentError):
            self.installation.update(REVISION, "new-app", "new-frontend", settings=settings)
        self.docker.fail_up = False
        with self.assertRaisesRegex(updater.DeploymentError, "unfinished"):
            self.installation.update(REVISION, "new-app", "new-frontend", settings={"POKETTO_EMAIL_DAILY_LIMIT": "90"})
        self.assertEqual(self.installation.update(REVISION, "new-app", "new-frontend")["status"], "healthy")
        self.assertIn("POKETTO_EMAIL_DAILY_LIMIT=80", self.docker.running["app"]["Config"]["Env"])

    def test_public_contact_reaches_only_frontend_and_survives_an_image_update(self):
        self.installation.update(REVISION, "new-app", "new-frontend", settings={"POKETTO_SUPPORT_EMAIL": "support@example.test"})
        self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertIn("POKETTO_SUPPORT_EMAIL=support@example.test", self.docker.running["frontend"]["Config"]["Env"])
        self.assertFalse(any(value.startswith("POKETTO_SUPPORT_EMAIL=") for value in self.docker.running["app"]["Config"]["Env"]))

    def test_identity_changes_do_not_allow_resource_changes(self):
        self.docker.changed_runtime = True
        with self.assertRaisesRegex(updater.DeploymentError, "runtime configuration"):
            self.installation.update(REVISION, "new-app", "new-frontend", settings={"POKETTO_EMAIL_DAILY_LIMIT": "80"})

    def test_invalid_identity_configuration_never_restarts_containers(self):
        for settings in ({"POKETTO_RESEND_API_KEY": "missing-from"}, {"POKETTO_GOOGLE_CLIENT_ID": "unpaired"},
                         {"POKETTO_EMAIL_DAILY_LIMIT": "0"}, {"POKETTO_EMAIL_DAILY_LIMIT": "100001"}):
            with self.subTest(settings=settings), self.assertRaises(updater.DeploymentError):
                self.installation.update(REVISION, "new-app", "new-frontend", settings=settings)
        self.assertFalse(any("up" in call for call in self.docker.calls))

    def test_settings_input_rejects_unrelated_duplicate_multiline_and_excessive_values(self):
        for payload in ("POKETTO_REPOSITORY_PASSWORD=keep-out", "POKETTO_EMAIL_FROM=a\nPOKETTO_EMAIL_FROM=b",
                        "POKETTO_EMAIL_FROM=a\rb", "POKETTO_EMAIL_FROM=a\x00b", "x" * 65537):
            with self.subTest(length=len(payload)), self.assertRaises(updater.DeploymentError):
                updater.read_settings(io.StringIO(payload))

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

    def test_a_healthy_update_retires_only_the_generations_this_installation_recorded(self):
        first = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(first["retiredImages"], 0)
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["knownImages"], ["id-new-app", "id-new-frontend", "id-old-app", "id-old-frontend"])
        self.assertIsNone(state["retirementError"])
        # The replaced generation is left with two digest references and with no reference at all.
        self.docker.images["id-old-app"]["refs"] = ["registry/poketto@sha256:old-a", "registry/poketto@sha256:old-b"]
        self.docker.images["id-old-frontend"]["refs"] = []
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["retiredImages"], 2)
        self.assertEqual(result["retiredImageIds"], ["id-old-app", "id-old-frontend"])
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["retiredImages"], ["id-old-app", "id-old-frontend"])
        self.assertEqual(state["knownImages"], ["id-new-app", "id-new-app-2", "id-new-frontend", "id-new-frontend-2"])
        # Images this installation never deployed stay, whatever label or reference they carry.
        for retained in ("id-new-app", "id-new-frontend", "id-new-app-2", "id-new-frontend-2", "id-stale",
                         "id-untagged", "id-retained-db", "id-retained-gateway", "id-sidecar", "id-foreign"):
            self.assertIn(retained, self.docker.images)
        self.assertEqual(self.docker.removed, ["registry/poketto@sha256:old-a", "id-old-frontend"])
        self.assertFalse(any("--force" in call or "-f" in call for call in self.docker.calls if call[1:3] == ("image", "rm")))
        removal = self.docker.calls.index(next(call for call in self.docker.calls if call[1:3] == ("image", "rm")))
        self.assertLess(max(index for index, call in enumerate(self.docker.calls) if "up" in call), removal)

    def test_a_retirement_failure_is_recorded_and_never_fails_a_healthy_deployment(self):
        self.docker.fail_containers = True
        result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["status"], "healthy")
        self.assertIsNone(result["retiredImages"])
        self.assertIn("docker", result["retirementError"])
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["status"], "healthy")
        self.assertEqual(state["retirementError"], result["retirementError"])
        self.assertEqual(state["retiredImages"], [])
        self.assertEqual(result["retiredImageIds"], [])
        self.docker.fail_containers = False
        self.installation.update(REVISION, "new-app", "new-frontend")
        state = json.loads(self.installation.state_file.read_text())
        self.assertIsNone(state["retirementError"])
        self.assertEqual(state["retiredImages"], [])

    def test_an_unanswered_pin_lookup_retires_nothing(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        # The previous version is pinned by image ID; its lookup is the one that goes unanswered.
        self.docker.fail_inspect = {"id-new-app"}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["status"], "healthy")
        self.assertIsNone(result["retiredImages"])
        self.assertIn("docker", result["retirementError"])
        for known in ("id-new-app", "id-new-frontend", "id-old-app", "id-old-frontend"):
            self.assertIn(known, self.docker.images)

    def test_an_unreadable_candidate_stays_known_while_the_others_go(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        self.docker.fail_inspect = {"id-old-frontend"}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["status"], "healthy")
        self.assertIsNone(result["retiredImages"])
        self.assertEqual(result["retiredImageIds"], ["id-old-app"])
        self.assertIn("1 candidate image", result["retirementError"])
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["retiredImages"], ["id-old-app"])
        self.assertEqual(state["retirementError"], result["retirementError"])
        self.assertNotIn("id-old-app", state["knownImages"])
        self.assertIn("id-old-frontend", state["knownImages"])
        self.assertNotIn("id-old-app", self.docker.images)

    def test_a_lost_retirement_record_never_fails_the_healthy_deployment(self):
        original = updater.write_json

        # Only the retirement record carries retirementError; the healthy state before it does not.
        def failing(path, value):
            if "retirementError" in value:
                raise OSError("no space left on device")
            original(path, value)

        with mock.patch.object(updater, "write_json", failing):
            result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["status"], "healthy")
        self.assertEqual(result["retiredImages"], 0)
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["status"], "healthy")
        self.assertNotIn("retirementError", state)

    def test_a_repointed_tag_never_retires_the_image_that_was_running(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        # A redelivery of the same commit points both tags at rebuilt images.
        for name in ("app", "frontend"):
            self.docker.images["id-new-" + name]["refs"] = []
            self.docker.images["id-rebuilt-" + name] = {"refs": ["new-" + name], "source": SOURCE}
        result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["status"], "healthy")
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["previousImages"], {"app": "id-new-app", "frontend": "id-new-frontend"})
        self.assertIn("id-new-app", self.docker.images)
        self.assertIn("id-new-frontend", self.docker.images)
        self.assertEqual(result["retiredImageIds"], ["id-old-app", "id-old-frontend"])

    def test_a_container_gone_since_the_listing_does_not_stop_the_retirement(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        self.docker.vanished_containers = {"sidecar-container"}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["retiredImageIds"], ["id-old-app", "id-old-frontend"])
        self.assertIsNone(json.loads(self.installation.state_file.read_text())["retirementError"])

    def test_a_removal_confirmed_only_by_the_next_run_is_still_counted(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        # The earlier run removed the image but was killed before it could confirm and record it.
        state = json.loads(self.installation.state_file.read_text())
        state["removing"] = ["id-old-app"]
        self.installation.state_file.write_text(json.dumps(state))
        del self.docker.images["id-old-app"]
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["retiredImageIds"], ["id-old-app", "id-old-frontend"])
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["removing"], [])
        self.assertNotIn("id-old-app", state["knownImages"])

    def test_a_known_image_that_is_already_gone_leaves_the_record(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        del self.docker.images["id-old-frontend"]
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["retiredImageIds"], ["id-old-app"])
        state = json.loads(self.installation.state_file.read_text())
        self.assertNotIn("id-old-frontend", state["knownImages"])

    def test_rerunning_the_running_version_keeps_the_previous_version_retained(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        result = self.installation.update(REVISION, "new-app", "new-frontend")
        self.assertEqual(result["status"], "healthy")
        self.assertEqual(result["retiredImages"], 0)
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["previousImages"], {"app": "id-old-app", "frontend": "id-old-frontend"})
        self.assertIn("id-old-app", self.docker.images)
        self.assertIn("id-old-frontend", self.docker.images)

    def test_redeploying_the_same_build_under_another_reference_keeps_the_previous_version(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        self.docker.images["id-new-app"]["refs"].append("registry/poketto:sha-" + REVISION)
        self.docker.images["id-new-frontend"]["refs"].append("registry/poketto-frontend:sha-" + REVISION)
        result = self.installation.update(
                REVISION, "registry/poketto:sha-" + REVISION, "registry/poketto-frontend:sha-" + REVISION)
        self.assertEqual(result["status"], "healthy")
        self.assertEqual(result["retiredImages"], 0)
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["previousImages"], {"app": "id-old-app", "frontend": "id-old-frontend"})
        self.assertIn("id-old-app", self.docker.images)

    def test_a_rebuilt_revision_records_the_replaced_build_as_previous(self):
        self.installation.update(REVISION, "new-app", "new-frontend")
        for reference in ("new-app-2", "new-frontend-2"):
            self.docker.images["id-" + reference] = {"refs": [reference], "source": SOURCE}
        result = self.installation.update(REVISION, "new-app-2", "new-frontend-2")
        self.assertEqual(result["status"], "healthy")
        state = json.loads(self.installation.state_file.read_text())
        self.assertEqual(state["previousImages"], {"app": "id-new-app", "frontend": "id-new-frontend"})
        self.assertIn("id-new-app", self.docker.images)
        self.assertNotIn("id-old-app", self.docker.images)
        self.assertEqual(result["retiredImages"], 2)

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
