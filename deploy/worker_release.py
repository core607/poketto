"""Install image-bound worker sources without replacing host configuration or tools."""

import hashlib
import json
import os
from pathlib import Path
import re
import stat
import tempfile
import urllib.request
import uuid


class DeploymentError(RuntimeError):
    def __init__(self, message, missing=False):
        super().__init__(message)
        self.missing = missing


SOURCES = frozenset((
    "worker.py", "launcher.py", "command_channel.py", "shell_loop.py", "disk_pool.py",
    "resource_pool.py", "bridge.py", "cli.py", "session_files.py", "binary_capture.py",
    "materialize.py", "artifacts.py",
))
PAYLOAD = SOURCES | {"requirements.txt"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def sync_directory(path):
    descriptor = os.open(path, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def protected(path, directory=False):
    if not path.is_absolute() or path.resolve() != path or path.is_symlink():
        raise DeploymentError("worker paths must be canonical and must not traverse symlinks")
    for parent in path.parents:
        ancestor = parent.stat()
        # A root-owned sticky directory protects root-owned children, including private /tmp fixtures.
        writable = ancestor.st_mode & 0o022 and not ancestor.st_mode & stat.S_ISVTX
        if ancestor.st_uid != 0 or writable:
            raise DeploymentError("worker path ancestors must prevent replacement by other users")
    info = path.stat()
    kind = stat.S_ISDIR if directory else stat.S_ISREG
    if not kind(info.st_mode) or info.st_uid != 0 or info.st_mode & 0o022:
        raise DeploymentError("worker paths must be root-owned and not writable by other users")


def read_bundle(path, revision):
    expected = PAYLOAD | {"revision", "SHA256SUMS"}
    if set(item.name for item in path.iterdir()) != expected:
        raise DeploymentError("worker image bundle must contain only the complete runtime sources")
    data = {}
    for name in expected:
        file = path / name
        if file.is_symlink() or not file.is_file() or file.stat().st_size > 2 * 1024 * 1024:
            raise DeploymentError("worker image bundle contains an invalid file")
        data[name] = file.read_bytes()
    if sum(map(len, data.values())) > 8 * 1024 * 1024:
        raise DeploymentError("worker image bundle exceeds its size bound")
    if data["revision"].decode("ascii").strip() != revision:
        raise DeploymentError("worker image bundle revision differs from the application")
    hashes = {}
    for line in data["SHA256SUMS"].decode("ascii").splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  ([a-z_]+\.(?:py|txt))", line)
        if not match or match[2] in hashes:
            raise DeploymentError("worker bundle checksums are invalid")
        hashes[match[2]] = match[1]
    if set(hashes) != PAYLOAD or any(sha(data[name]) != hashes[name] for name in PAYLOAD):
        raise DeploymentError("worker bundle checksum mismatch")
    for name in SOURCES:
        if b"\r" in data[name]:
            raise DeploymentError("worker sources require LF line endings")
        try:
            compile(data[name], name, "exec")
        except SyntaxError:
            raise DeploymentError("worker image bundle contains invalid Python source") from None
    return {name: data[name] for name in PAYLOAD}


def validate_config(config):
    if not isinstance(config, dict) or set(config) != {
            "sourceDirectory", "configFile", "python", "service", "healthUrl", "probeTokenFile", "probeCopyId"}:
        raise DeploymentError("executor deployment requires sourceDirectory, configFile, python, service, healthUrl, probeTokenFile and probeCopyId")
    for key in ("sourceDirectory", "configFile", "python", "probeTokenFile"):
        if not isinstance(config[key], str) or not re.fullmatch(r"/[A-Za-z0-9_./-]+", config[key]):
            raise DeploymentError("executor deployment paths must be absolute")
        path = Path(config[key])
        if ".." in path.parts or path == Path("/"):
            raise DeploymentError("invalid executor deployment path")
    if not isinstance(config["service"], str) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.@-]{0,120}\.service", config["service"]):
        raise DeploymentError("invalid executor service name")
    if not isinstance(config["healthUrl"], str) or not re.fullmatch(r"http://127\.0\.0\.1:[0-9]{1,5}/actuator/health/executor", config["healthUrl"]):
        raise DeploymentError("executor health URL must be the application's loopback executor health endpoint")
    if not 1 <= int(config["healthUrl"].split(":")[2].split("/")[0]) <= 65535:
        raise DeploymentError("invalid executor health port")
    try:
        if not isinstance(config["probeCopyId"], str) or str(uuid.UUID(config["probeCopyId"])) != config["probeCopyId"]:
            raise ValueError("invalid copy ID")
    except ValueError:
        raise DeploymentError("executor probe requires an existing copy UUID") from None


class NoRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, new_url):
        return None


class WorkerRelease:
    def __init__(self, config, state_directory, command):
        validate_config(config)
        self.config = config
        self.root = Path(config["sourceDirectory"])
        self.configuration = Path(config["configFile"])
        self.state_directory = state_directory
        self.command = command
        protected(self.root, directory=True)
        protected(self.configuration)
        settings = json.loads(self.configuration.read_bytes())
        if settings.get("launcher") != str(self.root / "launcher.py") or settings.get("supervisorUnit") != config["service"]:
            raise DeploymentError("executor deployment must match the installed launcher and supervisor")
        self.store = self.root / "releases"
        self.current = self.store / "current"

    def _probe_token(self):
        path = Path(self.config["probeTokenFile"])
        protected(path)
        if path.stat().st_mode & 0o077 or path.stat().st_size > 4096:
            raise DeploymentError("executor probe token must be a private root-owned file of at most 4096 bytes")
        token = path.read_text(encoding="ascii").strip()
        if not re.fullmatch(r"[!-~]{1,4096}", token):
            raise DeploymentError("executor probe token must be a nonempty single-line value")
        return token

    def _config_hash(self):
        protected(self.configuration)
        return sha(self.configuration.read_bytes())

    def _selected(self):
        if not self.current.is_symlink():
            raise DeploymentError("worker release selector is missing or is not a managed symlink")
        selected = os.readlink(self.current)
        if not re.fullmatch(r"[0-9a-f]{64}", selected) or self.current.lstat().st_uid != 0:
            raise DeploymentError("worker release selector is invalid")
        return selected

    def _sources(self, path):
        data = {}
        for name in SOURCES:
            file = path / name
            protected(file)
            if file.stat().st_size > 2 * 1024 * 1024:
                raise DeploymentError("installed worker source exceeds its size bound")
            data[name] = file.read_bytes()
        return data

    @staticmethod
    def _identity(data):
        return sha(json.dumps({name: sha(value) for name, value in data.items()}, sort_keys=True).encode())

    def _stage(self, data):
        if not self.store.exists():
            self.store.mkdir(mode=0o755)
            self.store.chmod(0o755)
        protected(self.store, directory=True)
        if self.store.stat().st_mode & 0o005 != 0o005:
            raise DeploymentError("worker release directory must be readable and traversable by the execution account")
        release = self._identity(data)
        target = self.store / release
        if not target.exists():
            with tempfile.TemporaryDirectory(prefix="incoming-", dir=self.store) as temporary:
                incoming = Path(temporary) / "sources"
                incoming.mkdir(mode=0o755)
                incoming.chmod(0o755)
                for name, value in data.items():
                    with (incoming / name).open("xb") as output:
                        output.write(value)
                        output.flush()
                        os.fsync(output.fileno())
                    (incoming / name).chmod(0o644)
                sync_directory(incoming)
                incoming.rename(target)
                sync_directory(self.store)
        protected(target, directory=True)
        actual = {}
        for file in target.iterdir():
            if file.name == "__pycache__":
                protected(file, directory=True)
                continue
            protected(file)
            if file.name not in PAYLOAD or file.stat().st_size > 2 * 1024 * 1024:
                raise DeploymentError("stored worker release contains an unexpected file")
            actual[file.name] = file.read_bytes()
        if actual != data:
            raise DeploymentError("stored worker release does not match its content identity")
        return release

    def prepare(self, image, revision):
        self._service_contract()
        self._probe_token()
        with tempfile.TemporaryDirectory(prefix="worker-image-", dir=self.state_directory) as temporary:
            container = self.command("docker", "create", "--network", "none", "--entrypoint", "/bin/true", image).strip()
            if not re.fullmatch(r"[0-9a-f]{64}", container):
                raise DeploymentError("worker bundle extraction did not create a container")
            try:
                self.command("docker", "cp", container + ":/opt/poketto-worker/.", temporary)
                data = read_bundle(Path(temporary), revision)
            finally:
                self.command("docker", "rm", "--volumes", container)
        requirements = {}
        for line in data["requirements.txt"].decode("ascii").splitlines():
            match = re.fullmatch(r"([A-Za-z0-9_-]+)==([A-Za-z0-9_.+-]+)", line)
            if not match or match[1] in requirements:
                raise DeploymentError("worker requirements must contain distinct pinned Python packages")
            requirements[match[1]] = match[2]
        # No installer or code from the image runs during extraction; dependencies stay operator-owned.
        try:
            self.command(self.config["python"], "-I", "-c",
                         "import importlib.metadata,json,sys; r=json.loads(sys.argv[1]); "
                         "sys.exit(0 if all(importlib.metadata.version(k)==v for k,v in r.items()) else 1)",
                         json.dumps(requirements, sort_keys=True))
        except DeploymentError:
            raise DeploymentError("worker Python dependencies are unavailable or differ from the image's pinned requirements") from None
        new_release = self._stage(data)
        if self.current.exists() or self.current.is_symlink():
            previous = self._selected()
            previous_data = self._sources(self.store / previous)
        else:
            previous_data = self._sources(self.root)
            previous = self._stage(previous_data)
        self._check_links(previous_data)
        return {"release": new_release, "previousRelease": previous, "configuration": self._config_hash(),
                "installation": sha(json.dumps(self.config, sort_keys=True).encode())}

    def _check_links(self, previous_data, allow_regular=True):
        for name in SOURCES:
            file = self.root / name
            if file.is_symlink():
                if os.readlink(file) != "releases/current/" + name or file.lstat().st_uid != 0:
                    raise DeploymentError("worker source link is not managed by this updater")
            elif allow_regular:
                protected(file)
                if file.read_bytes() != previous_data[name]:
                    raise DeploymentError("installed worker source changed during deployment")
            else:
                raise DeploymentError("worker sources are not all using the selected release")

    def _select(self, release):
        target = self.store / release
        protected(target, directory=True)
        temporary = self.store / "current.next"
        temporary.unlink(missing_ok=True)
        temporary.symlink_to(release, target_is_directory=True)
        temporary.replace(self.current)
        sync_directory(self.store)

    def _service_contract(self):
        expected = [self.config["python"], str(self.root / "worker.py"), "--config", str(self.configuration)]
        for name, suffix in (("ExecStart", []), ("ExecStopPost", ["--cleanup"])):
            definition = self.command("systemctl", "show", "--property=" + name, "--value", self.config["service"])
            commands = re.findall(r"argv\[\]=([^;]*);", definition)
            if len(commands) != 1 or commands[0].split() != expected + suffix:
                raise DeploymentError("executor service commands must use the configured Python, worker and configuration")

    def _active(self):
        self.command("systemctl", "is-active", "--quiet", self.config["service"])
        pid = self.command("systemctl", "show", "--property=MainPID", "--value", self.config["service"]).strip()
        if not re.fullmatch(r"[1-9][0-9]*", pid):
            raise DeploymentError("executor service has no running main process")
        arguments = (Path("/proc") / pid / "cmdline").read_bytes().split(b"\0")
        expected = [os.fsencode(self.config["python"]), os.fsencode(self.root / "worker.py"), b"--config", os.fsencode(self.configuration)]
        if arguments != expected + [b""]:
            raise DeploymentError("executor service is not running the configured worker sources")

    def install(self, plan):
        if self._config_hash() != plan["configuration"] or sha(json.dumps(self.config, sort_keys=True).encode()) != plan["installation"]:
            raise DeploymentError("private worker configuration changed during deployment")
        previous = plan["previousRelease"]
        selected = self._selected() if self.current.is_symlink() else previous
        if selected not in (previous, plan["release"]):
            raise DeploymentError("another worker release must be reconciled first")
        self._check_links(self._sources(self.store / selected))
        if self.current.is_symlink() and selected == plan["release"]:
            self._check_links({}, allow_regular=False)
            self.command("systemctl", "start", self.config["service"])
            self.verify(plan)
            return
        self.command("systemctl", "stop", self.config["service"])
        try:
            self._select(previous)
            # During first adoption every regular file and every new link still names the old bytes.
            # Only after all links exist does one atomic selector replacement expose the new generation.
            for name in SOURCES:
                target = self.root / name
                if not target.is_symlink():
                    temporary = self.root / (name + ".release-next")
                    temporary.unlink(missing_ok=True)
                    temporary.symlink_to("releases/current/" + name)
                    temporary.replace(target)
            sync_directory(self.root)
            self._select(plan["release"])
            self.command("systemctl", "start", self.config["service"])
            self.verify(plan)
        except Exception:
            self.command("systemctl", "stop", self.config["service"])
            self._select(previous)
            self.command("systemctl", "start", self.config["service"])
            self._active()
            raise DeploymentError("worker update failed; previous sources restored, deployment remains pending") from None

    def verify(self, plan):
        if (self._config_hash() != plan["configuration"] or self._selected() != plan["release"]
                or sha(json.dumps(self.config, sort_keys=True).encode()) != plan["installation"]):
            raise DeploymentError("worker sources or private configuration differ from the pending deployment")
        self._check_links({}, allow_regular=False)
        path = self.store / plan["release"]
        data = self._sources(path)
        requirements = path / "requirements.txt"
        protected(requirements)
        data["requirements.txt"] = requirements.read_bytes()
        if self._identity(data) != plan["release"]:
            raise DeploymentError("installed worker release checksum mismatch")
        self._active()

    def probe(self):
        # An explicit dedicated copy keeps release checks from creating, deleting or borrowing user copies.
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
            raise DeploymentError("executor read-only MCP probe failed; deployment remains pending") from None
