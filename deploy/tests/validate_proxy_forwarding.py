"""Required real Caddy/Tomcat/PostgreSQL gate. Owns only randomly named disposable Docker objects."""
import ipaddress
import hashlib
import json
import os
from pathlib import Path
import secrets
import signal
import subprocess
import sys
import tempfile
import time
from compose_ipam import unused_subnet, verify_compose_ipam

ROOT = Path(__file__).resolve().parents[2]
RUNTIME = ROOT / "build/acceptance/runtime"
OUTPUT = ROOT / "build/proxy-forwarding"
PYTHON = "python:3.12-slim-bookworm@sha256:782412e85d0f0984994c290652577d4018aff08145c85b262bb63dc0c7522254"
JAVA = "eclipse-temurin:26-jdk@sha256:c0fe66ea21e972724000cf402f8081c7841d960839f69cb0754f40b40f74b2cc"
RUN = secrets.token_hex(8)
LABEL = "io.poketto.proxy-check"
NETWORK = "poketto-proxy-" + RUN
CONTAINERS = []
deadline = time.monotonic() + 900
evidence = {"run": RUN, "checks": {}, "source": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()}


def docker(*args, timeout=60, check=True, env=None):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("proxy gate deadline exceeded")
    result = subprocess.run(["docker", *map(str, args)], cwd=ROOT, env=env, text=True, capture_output=True,
                            timeout=min(timeout, remaining))
    if check and result.returncode:
        raise RuntimeError(f"docker {args[0]} failed: {result.stderr.strip()[:1200]}")
    return result


def start(name, image, options, command=()):
    name = NETWORK + "-" + name
    CONTAINERS.append(name)
    docker("create", "--name", name, "--label", f"{LABEL}={RUN}", "--network", NETWORK,
           "--log-opt", "max-size=2m", "--log-opt", "max-file=1", *options, image, *command)
    docker("start", name)
    return name


def client(name, target, action, *args):
    result = docker("exec", clients[name], "python", "/client.py", target, action, *args, timeout=150)
    return json.loads(result.stdout)


def expect(name, actual, expected):
    if actual != expected:
        raise AssertionError(f"{name}: expected {expected}, got {actual}")
    evidence["checks"][name] = actual
    print("PASS " + name, flush=True)


def interrupted(signum, _frame):
    raise InterruptedError(f"proxy gate interrupted by signal {signum}")


def cleanup():
    # Inspect the ownership label even after an ambiguous create/start response.
    failures = []
    for name in reversed(CONTAINERS):
        inspection = subprocess.run(["docker", "inspect", "--format", '{{index .Config.Labels "' + LABEL + '"}}', name],
                                    text=True, capture_output=True, timeout=20)
        if inspection.returncode and "No such" not in inspection.stderr:
            failures.append(name + " ownership could not be checked")
        elif inspection.returncode == 0 and inspection.stdout.strip() == RUN:
            removed = subprocess.run(["docker", "rm", "-f", name], text=True, capture_output=True, timeout=30)
            if removed.returncode:
                failures.append(name)
    inspection = subprocess.run(["docker", "network", "inspect", "--format", '{{index .Labels "' + LABEL + '"}}', NETWORK],
                                text=True, capture_output=True, timeout=20)
    if inspection.returncode and "No such" not in inspection.stderr:
        failures.append(NETWORK + " ownership could not be checked")
    elif inspection.returncode == 0 and inspection.stdout.strip() == RUN:
        removed = subprocess.run(["docker", "network", "rm", NETWORK], text=True, capture_output=True, timeout=30)
        if removed.returncode:
            failures.append(NETWORK)
    if failures:
        raise RuntimeError("owned proxy gate cleanup failed: " + ", ".join(failures))
    evidence["cleanup"] = "PASS"


for sig in (signal.SIGINT, signal.SIGTERM):
    signal.signal(sig, interrupted)
OUTPUT.mkdir(parents=True, exist_ok=True)
evidence["inputSha256"] = {name: hashlib.sha256((ROOT / name).read_bytes()).hexdigest() for name in (
    "deploy/compose.yaml", "deploy/Caddyfile", "deploy/tests/proxy_client.py", "deploy/tests/validate_proxy_forwarding.py",
    "deploy/tests/compose_ipam.py",
    "src/integrationTest/java/io/github/core607/poketto/acceptance/ProxyRequestProbeConfiguration.java",
)}
try:
    if not (RUNTIME / "classes").is_dir():
        raise RuntimeError("run stageAcceptanceRuntime before this gate")
    docker("info", "--format", "{{.ServerVersion}}")
    pins = dict(line.split("=", 1) for line in (ROOT / "deploy/.env.example").read_text().splitlines()
                if line and not line.startswith("#") and "=" in line)
    for image in (JAVA, PYTHON, pins["POKETTO_DB_IMAGE"], pins["POKETTO_GATEWAY_IMAGE"]):
        if docker("image", "inspect", image, check=False).returncode:
            docker("pull", image, timeout=240)
    subnet = unused_subnet(docker, 24)
    pool = ipaddress.ip_network(f"{subnet.network_address + 128}/25")
    gateway_ip = str(subnet.network_address + 10)
    password = secrets.token_urlsafe(24)
    environment = os.environ.copy()
    environment.update({
        "COMPOSE_DISABLE_ENV_FILE": "1", "POKETTO_APP_IMAGE": JAVA, "POKETTO_FRONTEND_IMAGE": PYTHON,
        "POKETTO_GATEWAY_IMAGE": pins["POKETTO_GATEWAY_IMAGE"], "POKETTO_DB_IMAGE": pins["POKETTO_DB_IMAGE"],
        "POSTGRES_DB": "proxycheck", "POSTGRES_USER": "proxycheck", "POSTGRES_PASSWORD": password,
        "POKETTO_AUTH_INITIALIZATION_TOKEN": "synthetic-only", "POKETTO_PUBLIC_DOMAIN": "site.example.invalid",
        "POKETTO_REPOSITORY_REMOTE_URI": "https://example.invalid/synthetic.git",
        "POKETTO_REPOSITORY_USERNAME": "synthetic", "POKETTO_REPOSITORY_PASSWORD": "synthetic",
        "POKETTO_DATA_DIR_HOST": "/unused-proxy-data", "POKETTO_DB_DIR_HOST": "/unused-proxy-db",
        "POKETTO_GATEWAY_DIR_HOST": "/unused-proxy-gateway", "POKETTO_NETWORK_SUBNET": str(subnet),
        "POKETTO_NETWORK_DYNAMIC_RANGE": str(pool),
        "POKETTO_GATEWAY_INTERNAL_IP": gateway_ip,
    })
    # Consume production Compose's actual environment/IP binding rather than a copied test configuration.
    with tempfile.TemporaryDirectory(prefix="poketto-proxy-config-") as temporary:
        empty = Path(temporary) / "empty.env"
        empty.write_text("")
        configuration = json.loads(docker("compose", "--env-file", empty, "-f", ROOT / "deploy/compose.yaml",
                                          "config", "--format", "json", env=environment).stdout)
    app_environment = configuration["services"]["app"]["environment"]
    actual_gateway = configuration["services"]["gateway"]["networks"]["default"]["ipv4_address"]
    expect("gateway static address", actual_gateway, gateway_ip)
    expect("production subnet", configuration["networks"]["default"]["ipam"]["config"][0]["subnet"], str(subnet))
    ipam = configuration["networks"]["default"]["ipam"]["config"][0]
    expect("production dynamic pool", ipam["ip_range"], str(pool))
    evidence["composeAllocation"] = verify_compose_ipam(ROOT, OUTPUT, docker, environment, PYTHON, RUN)
    # The real forwarding topology uses the resolved production subnet AND dynamic pool.
    # Docker rejects overlapping existing networks; never alter another network to make room.
    docker("network", "create", "--label", f"{LABEL}={RUN}", "--subnet", ipam["subnet"],
           "--ip-range", ipam["ip_range"], NETWORK)
    actual_ipam = json.loads(docker("network", "inspect", NETWORK).stdout)[0]["IPAM"]["Config"][0]
    expect("real forwarding network dynamic pool", actual_ipam["IPRange"], ipam["ip_range"])
    expect("production loopback binding", configuration["services"]["app"]["ports"][0]["host_ip"], "127.0.0.1")
    common = ["--cap-drop", "ALL", "--security-opt", "no-new-privileges:true", "--read-only"]
    db = start("db", pins["POKETTO_DB_IMAGE"], ["--network-alias", "db", "--memory", "384m", "--cpus", "0.75",
        "--pids-limit", "128", "--tmpfs", "/var/lib/postgresql/data:size=192m", "--env", "POSTGRES_DB=proxycheck",
        "--env", "POSTGRES_USER=proxycheck", "--env", "POSTGRES_PASSWORD=" + password])
    for _ in range(60):
        if docker("exec", db, "pg_isready", "-U", "proxycheck", "-d", "proxycheck", check=False).returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("synthetic PostgreSQL did not become ready")
    app_environment.update({"POKETTO_ACCEPTANCE_ROOT": "/tmp/fixture", "POKETTO_ACCEPTANCE_PASSWORD": password,
        "POKETTO_ACCEPTANCE_ORIGIN": "http://gateway", "POKETTO_SESSION_COOKIE_SECURE": "false",
        "POKETTO_DATA_DIR": "/tmp/fixture/data", "POKETTO_SECURITY_ALLOWED_ORIGINS": "http://gateway"})
    app_environment.pop("POKETTO_AUTH_INITIALIZATION_TOKEN")
    options = [*common, "--network-alias", "app", "--memory", "768m", "--cpus", "1", "--pids-limit", "256",
               "--user", "65534:65534", "--tmpfs", "/tmp:size=128m,mode=1777", "--mount",
               f"type=bind,source={RUNTIME.as_posix()},target=/runtime,readonly"]
    for key, value in app_environment.items():
        options.extend(["--env", f"{key}={value}"])
    app = start("app", JAVA, options, ["java", "-Xmx512m", "-Djava.awt.headless=true", "-cp", "/runtime/classes:/runtime/jars/*",
        "io.github.core607.poketto.acceptance.AcceptanceApplication",
        "--spring.main.sources=io.github.core607.poketto.acceptance.ProxyRequestProbeConfiguration"])
    gateway = start("gateway", pins["POKETTO_GATEWAY_IMAGE"], [*common, "--network-alias", "gateway", "--ip", actual_gateway,
        "--user", "10002:10002", "--memory", "128m", "--cpus", "0.5", "--pids-limit", "128", "--cap-add", "NET_BIND_SERVICE",
        "--tmpfs", "/data:size=16m,uid=10002,gid=10002", "--tmpfs", "/config:size=16m,uid=10002,gid=10002",
        "--tmpfs", "/tmp:size=16m", "--env", "POKETTO_PUBLIC_DOMAIN=http://gateway", "--mount",
        f"type=bind,source={(ROOT / 'deploy/Caddyfile').as_posix()},target=/etc/caddy/Caddyfile,readonly"])
    for _ in range(120):
        if docker("exec", gateway, "wget", "-q", "-O", "/dev/null", "http://app:8080/actuator/health", check=False).returncode == 0:
            break
        time.sleep(1)
    else:
        raise RuntimeError("synthetic Spring did not become ready")
    clients = {}
    for index, name in enumerate("ABCD", start=20):
        clients[name] = start("client-" + name.lower(), PYTHON, [*common, "--ip", str(subnet.network_address + index),
            "--memory", "64m", "--cpus", "0.25", "--pids-limit", "32", "--tmpfs", "/tmp:size=8m",
            "--env", "FIXTURE_PASSWORD=" + password, "--mount",
            f"type=bind,source={(ROOT / 'deploy/tests/proxy_client.py').as_posix()},target=/client.py,readonly"],
            ["python", "-c", "import time; time.sleep(600)"])
    address_d = str(subnet.network_address + 23)
    expect("Caddy address and stripped forged port", client("D", "http://gateway", "probe"),
           {"address": address_d, "port": 80, "secure": False})
    expect("untrusted direct address and forged port/protocol ignored", client("D", "http://app:8080", "probe"),
           {"address": address_d, "port": 8080, "secure": False})
    expect("A forty attempts then throttled despite rotating XFF", client("A", "http://gateway", "attempts", "41", "a", "vary")["statuses"],
           [401] * 40 + [429])
    expect("B independent Caddy bucket", client("B", "http://gateway", "attempts", "1", "b", "vary")["statuses"], [401])
    expect("A spoofed XFF cannot reopen bucket", client("A", "http://gateway", "attempts", "1", "again", "vary")["statuses"], [429])
    expect("direct C forty attempts then throttled despite rotating XFF", client("C", "http://app:8080", "attempts", "41", "c", "vary")["statuses"],
           [401] * 40 + [429])
    expect("shared username first six", client("B", "http://gateway", "attempts", "6", "shared", "same")["statuses"], [401] * 6)
    expect("shared username across IP reaches ten", client("D", "http://gateway", "attempts", "5", "shared", "same")["statuses"], [401] * 4 + [429])
    evidence["status"] = "PASS"
finally:
    try:
        if "app" in globals():
            logs = docker("logs", app, check=False)
            (OUTPUT / "spring.log").write_text(logs.stdout + logs.stderr, encoding="utf-8")
    finally:
        cleanup()
        (OUTPUT / "evidence.json").write_text(json.dumps(evidence, indent=2), encoding="utf-8")
