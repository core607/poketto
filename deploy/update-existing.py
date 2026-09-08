#!/usr/bin/env python3
"""Update app/frontend images in a root-owned existing Compose installation."""

import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.request


class DeploymentError(RuntimeError):
    pass


def run(*args):
    result = subprocess.run(args, capture_output=True, text=True, timeout=240)
    if result.returncode:
        # Compose configuration and logs can contain operator credentials.
        raise DeploymentError("deployment command failed: " + args[0])
    return result.stdout


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as output:
        os.chmod(temporary, 0o600)
        json.dump(value, output, sort_keys=True, indent=2)
        output.write("\n")
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, path)
    descriptor = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def runtime_contract(container):
    config = container["Config"]
    host = container["HostConfig"]
    return {
        "environment": sorted(item for item in config.get("Env", []) if not item.startswith("POKETTO_REVISION=")),
        "user": config.get("User"),
        "workingDirectory": config.get("WorkingDir"),
        "host": {key: host.get(key) for key in (
            "Memory", "MemorySwap", "NanoCpus", "CpuShares", "PidsLimit", "ReadonlyRootfs",
            "PortBindings", "Tmpfs", "NetworkMode", "RestartPolicy", "LogConfig", "ShmSize", "GroupAdd")},
        "capabilities": {key: sorted(host.get(key) or []) for key in ("CapAdd", "CapDrop", "SecurityOpt")},
        "mounts": sorted((mount["Type"], mount.get("Source"), mount["Destination"], mount["RW"])
                         for mount in container.get("Mounts", [])),
    }


class Installation:
    def __init__(self, root, config, command=run):
        self.root = root
        self.config = config
        self.command = command
        self.state_dir = root / ".deployment"
        self.state_dir.mkdir(mode=0o700, exist_ok=True)
        self.overlay = self.state_dir / "images.json"
        self.state_file = self.state_dir / "state.json"

    def compose(self, *args, overlay=None):
        command = ["docker", "compose", "--project-directory", str(self.root), "--project-name", self.config["project"]]
        for name in self.config["composeFiles"]:
            command.extend(["-f", str(self.root / name)])
        selected = overlay if overlay is not None else self.overlay
        if selected.exists():
            command.extend(["-f", str(selected)])
        return self.command(*command, *args)

    def containers(self):
        ids = self.command("docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + self.config["project"]).split()
        if not ids:
            raise DeploymentError("configured Compose project has no containers")
        result = {}
        for item in json.loads(self.command("docker", "inspect", *ids)):
            name = item["Config"]["Labels"].get("com.docker.compose.service")
            if not name or name in result:
                raise DeploymentError("each configured service must have one container")
            result[name] = item
        if not {"app", "frontend"}.issubset(result):
            raise DeploymentError("existing app and frontend containers are required")
        return result

    def image(self, reference, revision):
        image = json.loads(self.command("docker", "image", "inspect", reference))[0]
        if (image["Config"].get("Labels") or {}).get("org.opencontainers.image.revision") != revision:
            raise DeploymentError("loaded image revision does not match the requested commit")
        return image["Id"]

    def check_declared_environment(self, rendered, containers):
        for service in ("app", "frontend"):
            actual = dict(value.split("=", 1) for value in containers[service]["Config"].get("Env", []))
            declared = rendered["services"][service].get("environment", {})
            if any(actual.get(key) != str(value) for key, value in declared.items()):
                raise DeploymentError("declared environment differs from the running installation")

    def verify(self, state):
        current = self.containers()
        for service in ("app", "frontend"):
            container = current[service]
            if container["Image"] != state["imageIds"][service]:
                raise DeploymentError("running image does not match the selected image")
            if container["State"].get("Health", {}).get("Status") != "healthy":
                raise DeploymentError("updated service is not healthy")
            if digest(runtime_contract(container)) != state["runtimeContracts"][service]:
                raise DeploymentError("runtime configuration changed beyond the selected images")
        others = {name: value["Id"] for name, value in current.items() if name not in ("app", "frontend")}
        if others != state["otherContainers"]:
            raise DeploymentError("an unrelated Compose container changed")
        if digest(json.loads(self.compose("config", "--format", "json"))) != state["configuration"]:
            raise DeploymentError("installation configuration changed during deployment")
        for check in self.config.get("healthChecks", []):
            try:
                with urllib.request.urlopen(check["url"], timeout=15) as response:
                    body = response.read(1024 * 1024)
                    if response.status != 200:
                        raise DeploymentError("application health entrance did not return 200")
                    if check.get("status") and json.loads(body).get("status") != check["status"]:
                        raise DeploymentError("application readiness is not confirmed")
            except (OSError, ValueError) as error:
                raise DeploymentError("application health entrance is unavailable") from error

    def update(self, revision, app_image, frontend_image, check_only=False):
        image_refs = {"app": app_image, "frontend": frontend_image}
        image_ids = {name: self.image(reference, revision) for name, reference in image_refs.items()}
        containers = self.containers()
        before = json.loads(self.compose("config", "--format", "json"))
        self.check_declared_environment(before, containers)
        candidate = self.state_dir / "candidate.json"
        write_json(candidate, {"services": {name: {"image": ref} for name, ref in image_refs.items()}})
        rendered = json.loads(self.compose("config", "--format", "json", overlay=candidate))
        comparable = json.loads(json.dumps(rendered))
        for name in image_refs:
            comparable["services"][name]["image"] = before["services"][name]["image"]
        if comparable != before:
            raise DeploymentError("candidate changes more than app/frontend images")
        state = json.loads(self.state_file.read_text()) if self.state_file.exists() else None
        if state and state["status"] == "pending":
            if state["revision"] != revision or state["imageIds"] != image_ids or state["configuration"] != digest(rendered):
                raise DeploymentError("an unfinished deployment requires reconciliation with the same images and configuration")
        else:
            state = {
                "status": "pending", "revision": revision, "images": image_refs, "imageIds": image_ids,
                "configuration": digest(rendered),
                "previousImages": {name: containers[name]["Config"]["Image"] for name in image_refs},
                "runtimeContracts": {name: digest(runtime_contract(containers[name])) for name in image_refs},
                "otherContainers": {name: value["Id"] for name, value in containers.items() if name not in image_refs},
            }
        if check_only:
            candidate.unlink()
            return {"status": "validated", "revision": revision}
        write_json(self.state_file, state)
        write_json(self.overlay, json.loads(candidate.read_text()))
        candidate.unlink()
        self.compose("up", "--detach", "--no-deps", "--no-build", "--pull", "never", "--wait",
                     "--wait-timeout", str(self.config.get("healthTimeoutSeconds", 180)), "app", "frontend")
        self.verify(state)
        state["status"] = "healthy"
        write_json(self.state_file, state)
        return {"status": "healthy", "revision": revision, "imageIds": image_ids}


def load_config(root):
    if not root.is_absolute() or root.is_symlink() or root.resolve() != root or root == Path("/"):
        raise DeploymentError("deployment root must be a canonical absolute directory")
    path = root / "deployment.json"
    for item in (root, path):
        info = item.stat()
        if item.is_symlink() or info.st_uid != 0 or info.st_mode & 0o022:
            raise DeploymentError("deployment root and configuration must be root-owned and not writable by other users")
    config = json.loads(path.read_text())
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]{0,62}", config.get("project", "")):
        raise DeploymentError("invalid Compose project")
    files = config.get("composeFiles", [])
    if not isinstance(files, list) or not 1 <= len(files) <= 8:
        raise DeploymentError("one to eight Compose files are required")
    for name in files:
        if not isinstance(name, str) or Path(name).is_absolute() or ".." in Path(name).parts:
            raise DeploymentError("Compose files must remain below the deployment root")
        file = root / name
        if not file.is_file() or not file.resolve().is_relative_to(root):
            raise DeploymentError("Compose files must remain below the deployment root")
    timeout = config.get("healthTimeoutSeconds", 180)
    if type(timeout) is not int or not 1 <= timeout <= 210:
        raise DeploymentError("health timeout must be between 1 and 210 seconds")
    for check in config.get("healthChecks", []):
        if not re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}/[^\s]*", check.get("url", "")):
            raise DeploymentError("health checks must use a loopback HTTP entrance")
    return config


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--app-image", required=True)
    parser.add_argument("--frontend-image", required=True)
    parser.add_argument("--app-revision", required=True)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if os.geteuid() != 0:
        raise DeploymentError("the existing-installation updater requires its configured privileged entrance")
    if not re.fullmatch(r"[0-9a-f]{40}", args.app_revision):
        raise DeploymentError("a full source commit is required")
    for image in (args.app_image, args.frontend_image):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._:/@-]{0,255}", image):
            raise DeploymentError("invalid image reference")
    config = load_config(args.root)
    installation = Installation(args.root, config)
    with (installation.state_dir / "lock").open("a") as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise DeploymentError("another deployment holds the installation lock") from error
        print(json.dumps(installation.update(args.app_revision, args.app_image, args.frontend_image, args.check)))


if __name__ == "__main__":
    try:
        main()
    except (DeploymentError, OSError, ValueError, KeyError, subprocess.TimeoutExpired) as error:
        message = str(error) if isinstance(error, DeploymentError) else "deployment could not be verified; reconcile the protected state before retrying"
        print("existing deployment: " + message, file=sys.stderr)
        sys.exit(1)
