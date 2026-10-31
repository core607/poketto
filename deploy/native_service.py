"""Verify an independently installed native service without installing or restarting it."""

import hashlib
import json
import os
from pathlib import Path
import re
import socket
import stat
import struct
import sys
import urllib.request
import uuid


class DeploymentError(RuntimeError):
    def __init__(self, message, missing=False):
        super().__init__(message)
        self.missing = missing


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


def sha(data):
    return hashlib.sha256(data).hexdigest()


def parse_deployment_state(raw):
    """Read either retained legacy state or native schema 2 without adopting or rewriting it."""
    if len(raw) > 16 * 1024 * 1024:
        raise DeploymentError("deployment state exceeds its bound")
    try:
        state = json.loads(raw)
    except ValueError:
        raise DeploymentError("deployment state is not valid JSON") from None
    if not isinstance(state, dict) or state.get("status") not in ("pending", "healthy"):
        raise DeploymentError("deployment state must be reconciled before continuing")
    images = state.get("imageIds")
    if (not isinstance(images, dict) or set(images) != {"app", "frontend"}
            or any(not isinstance(value, str) or not 1 <= len(value) <= 256 for value in images.values())):
        raise DeploymentError("deployment state requires the retained application image identities")
    for key, length in (("revision", 40), ("configuration", 64)):
        value = state.get(key)
        if not isinstance(value, str) or not re.fullmatch(r"[0-9a-f]{" + str(length) + "}", value):
            raise DeploymentError("deployment state requires its exact revision and configuration digest")
    if "schemaVersion" not in state:
        if "nativeDependency" in state:
            raise DeploymentError("unsupported or mixed deployment state schema")
        return state
    if type(state["schemaVersion"]) is not int or state["schemaVersion"] != 2 or "worker" in state:
        raise DeploymentError("unsupported or mixed deployment state schema")
    dependency = state.get("nativeDependency")
    if dependency is not None and (not isinstance(dependency, dict) or dependency.get("kind") != "sandcrab-native-v1"):
        raise DeploymentError("unsupported native dependency state")
    return state


def protected(path, directory=False):
    if not path.is_absolute() or path.resolve() != path or path.is_symlink():
        raise DeploymentError("native dependency paths must be canonical and contain no symlinks")
    for parent in path.parents:
        info = parent.stat()
        writable = info.st_mode & 0o022 and not info.st_mode & stat.S_ISVTX
        if info.st_uid != 0 or writable:
            raise DeploymentError("native dependency ancestors must prevent replacement by other users")
    info = path.stat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if not kind(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise DeploymentError("native dependency files must be root-owned and protected from other users")


def bounded_file(path, maximum, private=False, executable=False):
    protected(path)
    with path.open("rb") as stream:
        before = os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_uid != 0 or before.st_mode & 0o022:
            raise DeploymentError("native dependency changed before reading")
        if before.st_nlink != 1 or private and before.st_mode & 0o077:
            raise DeploymentError("private dependency files require one link and owner-only permissions")
        if executable and before.st_mode & 0o111 != 0o111:
            raise DeploymentError("native binary must be executable")
        value = stream.read(maximum + 1)
        after = os.fstat(stream.fileno())
    current = path.stat()
    identity = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns, info.st_ctime_ns)
    if len(value) > maximum or identity(before) != identity(after) or identity(after) != identity(current):
        raise DeploymentError("native dependency is oversized or changed while reading")
    return value


def validate_config(config):
    keys = {"binary", "binarySha256", "configFile", "configSha256", "service", "socket",
            "poolChecker", "poolCheckerSha256", "healthUrl", "probeTokenFile", "probeCopyId"}
    if not isinstance(config, dict) or set(config) != keys:
        raise DeploymentError("native execution requires pinned binary, config, pool checker, service, socket and probe settings")
    for key in ("binary", "configFile", "poolChecker", "socket", "probeTokenFile"):
        value = config[key]
        if not isinstance(value, str) or not re.fullmatch(r"/[A-Za-z0-9_./-]+", value):
            raise DeploymentError("native dependency paths must be absolute")
        if value == "/" or any(part in ("", ".", "..") for part in value.split("/")[1:]):
            raise DeploymentError("native dependency paths must be normalized")
    for key in ("binarySha256", "configSha256", "poolCheckerSha256"):
        if not isinstance(config[key], str) or not re.fullmatch(r"[0-9a-f]{64}", config[key]):
            raise DeploymentError("native dependencies require exact SHA-256 pins")
    if not isinstance(config["service"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,120}\.service", config["service"]):
        raise DeploymentError("invalid native service name")
    health = config["healthUrl"]
    if not isinstance(health, str) or not re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}/actuator/health/executor", health):
        raise DeploymentError("native health URL must be the application's loopback executor endpoint")
    if not 1 <= int(health.split(":")[2].split("/")[0]) <= 65535:
        raise DeploymentError("invalid native health port")
    try:
        if not isinstance(config["probeCopyId"], str) or str(uuid.UUID(config["probeCopyId"])) != config["probeCopyId"]:
            raise ValueError("invalid copy identity")
    except ValueError:
        raise DeploymentError("native probe requires an existing dedicated copy UUID") from None


class NativeService:
    KIND = "sandcrab-native-v1"

    def __init__(self, config, command):
        validate_config(config)
        self.config = dict(config)
        self.command = command

    def _files(self):
        checks = (("binary", "binarySha256", 64 * 1024 * 1024, False, True),
                  ("configFile", "configSha256", 1024 * 1024, True, False),
                  ("poolChecker", "poolCheckerSha256", 128 * 1024, False, False))
        for path, expected, maximum, private, executable in checks:
            value = bounded_file(Path(self.config[path]), maximum, private, executable)
            if sha(value) != self.config[expected]:
                raise DeploymentError("installed native dependency differs from its checksum pin")

    def _process(self):
        self.command("systemctl", "is-active", "--quiet", self.config["service"])
        pid = self.command("systemctl", "show", "--property=MainPID", "--value", self.config["service"]).strip()
        if not re.fullmatch(r"[1-9][0-9]{0,9}", pid):
            raise DeploymentError("native service has no running main process")
        process = Path("/proc") / pid
        if process.stat().st_uid != 0:
            raise DeploymentError("native service main process must belong to root")
        before = (process / "stat").read_text().rsplit(")", 1)[1].split()[19]
        expected = [os.fsencode(self.config["binary"]), b"serve", b"--config", os.fsencode(self.config["configFile"]), b""]
        with (process / "cmdline").open("rb") as stream:
            arguments = stream.read(8193)
        if len(arguments) > 8192 or arguments.split(b"\0") != expected:
            raise DeploymentError("native service does not run the declared binary and configuration path")
        executable = process / "exe"
        if os.readlink(executable) != self.config["binary"]:
            raise DeploymentError("running native executable differs from the installed path")
        with executable.open("rb") as stream:
            value = stream.read(64 * 1024 * 1024 + 1)
        if len(value) > 64 * 1024 * 1024 or sha(value) != self.config["binarySha256"]:
            raise DeploymentError("running native executable differs from its checksum pin")
        after = (process / "stat").read_text().rsplit(")", 1)[1].split()[19]
        if before != after:
            raise DeploymentError("native service process changed during verification")
        return {"pid": int(pid), "startTicks": after}

    def _socket(self, process):
        path = Path(self.config["socket"])
        protected(path.parent, directory=True)
        if path.is_symlink():
            raise DeploymentError("native socket must not be a symbolic link")
        self._socket_peer(path, process)

    @staticmethod
    def _socket_peer(path, process):
        info = path.stat()
        if not stat.S_ISSOCK(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o007:
            raise DeploymentError("native socket must be root-owned and closed to other accounts")
        directory = os.open(path.parent, os.O_RDONLY | os.O_DIRECTORY)
        try:
            owner = os.fstat(directory)
            if owner.st_uid != 0 or owner.st_mode & 0o022:
                raise DeploymentError("native socket parent must remain root-owned and protected")
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.settimeout(5)
                # The directory FD also avoids overflowing sockaddr_un through a /proc/PID/root prefix.
                connection.connect("/proc/self/fd/" + str(directory) + "/" + path.name)
                pid, uid, _ = struct.unpack("3i", connection.getsockopt(socket.SOL_SOCKET, socket.SO_PEERCRED, 12))
        finally:
            os.close(directory)
        if pid != process["pid"] or uid != 0:
            raise DeploymentError("native socket peer is not the selected service main process")

    def verify_consumer(self, container_pid, container_socket):
        if type(container_pid) is not int or container_pid < 1:
            raise DeploymentError("application container has no running init process")
        if not isinstance(container_socket, str):
            raise DeploymentError("application socket path must be declared")
        name = Path(container_socket)
        if not name.is_absolute() or ".." in name.parts:
            raise DeploymentError("application socket path must be absolute and normalized")
        # Resolve the actual container mount namespace, not Docker's stored source-path text.
        path = Path("/proc") / str(container_pid) / "root" / name.relative_to("/")
        host = Path(self.config["socket"]).stat()
        mounted = path.stat()
        if (host.st_dev, host.st_ino) != (mounted.st_dev, mounted.st_ino):
            raise DeploymentError("application socket inode differs from the pinned native endpoint")
        process = self._process()
        self._socket_peer(path, process)
        if self._process() != process:
            raise DeploymentError("native service restarted while verifying its application consumer")

    def inspect(self):
        self._files()
        process = self._process()
        self._socket(process)
        self.command(sys.executable, self.config["poolChecker"], "--service", self.config["service"])
        self._files()
        if self._process() != process:
            raise DeploymentError("native service restarted during this observation")
        # A current config-file digest is not proof of the bytes loaded by an existing process.
        # The application's live API health and dedicated-copy probe are separate requirements.
        return {"kind": self.KIND, "installation": sha(json.dumps(self.config, sort_keys=True).encode()),
                "service": self.config["service"], "binary": self.config["binary"],
                "binarySha256": self.config["binarySha256"], "configFile": self.config["configFile"],
                "installedConfigSha256": self.config["configSha256"],
                "poolCheckerSha256": self.config["poolCheckerSha256"]}

    def verify(self, expected):
        if not isinstance(expected, dict) or expected.get("kind") != self.KIND or self.inspect() != expected:
            raise DeploymentError("native dependency differs from the pending deployment")

    def _probe_token(self):
        token = bounded_file(Path(self.config["probeTokenFile"]), 4096, private=True).decode("ascii").strip()
        if not re.fullmatch(r"[!-~]{1,4096}", token):
            raise DeploymentError("native probe token must be a nonempty single-line value")
        return token

    def probe(self):
        body = {"jsonrpc": "2.0", "id": "deployment-probe", "method": "tools/call", "params": {
            "name": "repo_exec", "arguments": {"expectedCopyId": self.config["probeCopyId"],
                "command": "poketto --help >/dev/null && printf poketto-deployment-ready", "timeoutSeconds": 30}}}
        url = self.config["healthUrl"].removesuffix("/actuator/health/executor") + "/mcp"
        request = urllib.request.Request(url, data=json.dumps(body).encode(), method="POST", headers={
            "Authorization": "Bearer " + self._probe_token(), "Content-Type": "application/json",
            "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-11-25",
            "User-Agent": "poketto-deployment/1"})
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirects())
        try:
            with opener.open(request, timeout=75) as response:
                raw = response.read(1024 * 1024 + 1)
                if response.status != 200 or len(raw) > 1024 * 1024:
                    raise ValueError("invalid probe response")
            envelope = json.loads(raw)
            result = envelope["result"]
            if envelope.get("id") != "deployment-probe" or envelope.get("error") or result.get("isError"):
                raise ValueError("unsuccessful probe response")
            content = next(item["text"] for item in result["content"] if item.get("type") == "text")
            answer = json.loads(content)
            if (answer.get("copyId") != self.config["probeCopyId"] or answer.get("exitCode") != 0
                    or answer.get("stdout") != "poketto-deployment-ready"):
                raise ValueError("probe command did not complete")
        except (OSError, ValueError, KeyError, TypeError, AttributeError, StopIteration):
            raise DeploymentError("native read-only MCP probe failed; deployment remains pending") from None
