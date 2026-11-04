"""Verify the app image's pinned release against an independently managed repository service."""

import json
import os
from pathlib import Path
import re
import socket
import stat
import struct
import uuid

from worker_release import DeploymentError, RepositoryProbe, protected, sha, validate_executor_settings


def validate_config(config):
    paths = ("binary", "configFile", "socket", "probeTokenFile")
    if not isinstance(config, dict) or set(config) != {*paths, "configSha256", "service", "healthUrl", "probeCopyId"}:
        raise DeploymentError("nativeExecutor requires binary, configFile, configSha256, socket, service and probe settings")
    validate_executor_settings(config, paths)
    if not isinstance(config["configSha256"], str) or not re.fullmatch(r"[0-9a-f]{64}", config["configSha256"]):
        raise DeploymentError("native executor configuration requires a SHA-256 pin")


def release_pin(text):
    lines = text.splitlines()
    fields = dict(line.split("=", 1) for line in lines if "=" in line)
    if (len(text) > 1024 or len(lines) != 3 or set(fields) != {"version", "bundleSha256", "sandcrabSha256"}
            or not re.fullmatch(r"v[0-9]+\.[0-9]+\.[0-9]+", fields["version"])
            or any(not re.fullmatch(r"[0-9a-f]{64}", fields[k]) for k in ("bundleSha256", "sandcrabSha256"))):
        raise DeploymentError("candidate image has an invalid Sandcrab release pin")
    return fields


def checked_file(path, limit, expected):
    protected(path)
    with path.open("rb") as source:
        data = source.read(limit + 1)
        identity = os.fstat(source.fileno())
    if len(data) > limit or sha(data) != expected:
        raise DeploymentError("installed native dependency differs from its checksum pin")
    return identity


def consumer_socket(container, environment, host):
    path = Path(environment.get("POKETTO_EXECUTOR_SOCKET", ""))
    if not path.is_absolute() or ".." in path.parts or path.name != host.name:
        raise DeploymentError("configure the application socket inside its read-only directory bind")
    mounts = container.get("Mounts", [])
    if (not any(m.get("Type") == "bind" and m["Destination"] == str(path.parent)
                and m.get("Source") == str(host.parent) and not m["RW"] for m in mounts)
            or any(m["Destination"] == str(path) for m in mounts)):
        raise DeploymentError("the application must bind the native socket parent read-only")
    if environment.get("POKETTO_EXECUTOR_SIGNING_KEY") or any(
            m["Destination"] == "/run/poketto-secrets/executor-private.pem" for m in mounts):
        raise DeploymentError("remove the signed executor key from the native application")
    for key in ("STAGING_DIRECTORY", "TRANSFERS_DIRECTORY", "COPIES_METADATA_ROOT"):
        directory = Path(environment.get("POKETTO_EXECUTOR_" + key, ""))
        covering = [m for m in mounts if directory.is_relative_to(m["Destination"])]
        if (not directory.is_absolute() or ".." in directory.parts or not covering
                or not max(covering, key=lambda m: len(Path(m["Destination"]).parts))["RW"]):
            raise DeploymentError("native application directories require writable mounts: " + key)
    pid = container["State"].get("Pid")
    if type(pid) is not int or pid < 1 or not host.samefile(Path("/proc") / str(pid) / "root" / path.relative_to("/")):
        raise DeploymentError("the running application socket differs from the installed native endpoint")
    return str(path)


class NativeRepository(RepositoryProbe):
    def __init__(self, config, command):
        validate_config(config)
        self.config = config
        self.command = command

    def process(self, binary, inode):
        result = self.command("systemctl", "show", "--property=ActiveState,MainPID,ControlGroup", self.config["service"])
        unit = dict(line.split("=", 1) for line in result.splitlines())
        group = unit.get("ControlGroup", "")
        if (unit.get("ActiveState") != "active" or not re.fullmatch(r"[1-9][0-9]*", unit.get("MainPID", ""))
                or not group.startswith("/") or Path(group).name != self.config["service"]):
            raise DeploymentError("the selected native service has no active systemd process")
        pid = int(unit["MainPID"])
        proc = Path("/proc") / str(pid)
        arguments = [os.fsencode(binary), b"serve", b"--config", os.fsencode(self.config["configFile"]), b""]
        groups = (proc / "cgroup").read_text().splitlines()
        if (not any(line == "0::" + group or line.startswith("0::" + group + "/") for line in groups)
                or (proc / "cmdline").read_bytes().split(b"\0") != arguments
                or not os.path.samestat((proc / "exe").stat(), inode)):
            raise DeploymentError("running native process differs from the pinned binary, configuration or cgroup")
        return pid, (proc / "stat").read_text().rsplit(")", 1)[1].split()[19]

    def inspect(self, image, container, environment):
        self._probe_token()
        pin = release_pin(self.command(
            "docker", "run", "--rm", "--network", "none", "--read-only", "--cap-drop", "ALL",
            "--security-opt", "no-new-privileges", "--user", "65534:65534", "--pids-limit", "8", "--memory", "32m",
            "--entrypoint", "/usr/bin/head", image, "-c", "1025", "/opt/poketto/sandcrab-release.properties"))
        binary = Path(self.config["binary"])
        inode = checked_file(binary, 64 * 1024 * 1024, pin["sandcrabSha256"])
        if not inode.st_mode & stat.S_IXUSR:
            raise DeploymentError("the pinned native binary must be executable")
        checked_file(Path(self.config["configFile"]), 1024 * 1024, self.config["configSha256"])
        process = self.process(binary, inode)
        host = Path(self.config["socket"])
        protected(host.parent, directory=True)
        info = host.lstat()
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o007:
            raise DeploymentError("native endpoint must be a root-owned Unix socket closed to other accounts")
        path = consumer_socket(container, environment, host)
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as peer:
            peer.settimeout(5)
            peer.connect(str(host))
            pid, uid, _ = struct.unpack("3i", peer.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        if pid != process[0] or uid != 0:
            raise DeploymentError("native socket peer differs from the selected systemd service")
        if not re.fullmatch(r"[1-9][0-9]*", self.command("docker", "exec", container["Id"], "id", "-u").strip()):
            raise DeploymentError("native repository access requires an unprivileged application user")
        live = json.loads(self.command("docker", "exec", container["Id"], "curl", "--disable", "--noproxy", "*",
                                       "--silent", "--fail", "--max-time", "10", "--max-filesize", "1048576",
                                       "--unix-socket", path, "http://localhost/v1/info"))
        profile = environment.get("POKETTO_EXECUTOR_PROFILE", "repository")
        builder = environment.get("POKETTO_EXECUTOR_TEMPLATE_PROFILE", profile)
        selected = live["profiles"].get(profile, {})
        if (live.get("apiVersion") != 1 or str(uuid.UUID(live["serverId"])) != live["serverId"]
                or not {"sessions", "templates", "files", "uploads", "calls"} <= set(live["capabilities"])
                or selected.get("hostCalls") is not True or selected.get("network") != "none"):
            raise DeploymentError("the native API does not offer the selected offline repository profile")
        if live["profiles"].get(builder, {}).get("network") != "none":
            raise DeploymentError("the native API does not offer the selected offline template preparation profile")
        if self.process(binary, inode) != process:
            raise DeploymentError("native service restarted during verification")
        receipt = {"release": pin["version"], "binarySha256": pin["sandcrabSha256"], "profile": profile,
                   "configSha256": self.config["configSha256"], "installation": sha(json.dumps(self.config, sort_keys=True).encode())}
        if builder != profile:
            receipt["templateProfile"] = builder
        return receipt
