"""Exercise production Compose dependency order and IPAM with bounded inert service payloads."""
import ipaddress
import json
import secrets
import subprocess


def unused_subnet(docker, prefix):
    ids = docker("network", "ls", "-q").stdout.split()
    networks = json.loads(docker("network", "inspect", *ids).stdout) if ids else []
    occupied = [ipaddress.ip_network(item["Subnet"]) for network in networks
                for item in network["IPAM"].get("Config", []) if item.get("Subnet")]
    for _ in range(16):
        candidate = ipaddress.ip_network(f"10.{secrets.randbelow(200) + 20}.{secrets.randbelow(250)}.0/{prefix}")
        if not any(other.version == 4 and candidate.overlaps(other) for other in occupied):
            return candidate
    raise RuntimeError("no unused synthetic IPAM subnet found")


def verify_compose_ipam(root, output, docker, environment, image, run):
    label = "io.poketto.ipam-check"
    results = []
    empty = output / "ipam-empty.env"
    empty.write_text("", encoding="utf-8")
    for index, (prefix, offset, pool_offset, pool_prefix) in enumerate((
            (24, 2, 128, 25), (24, 3, 128, 25), (24, 4, 128, 25),
            (28, 2, 8, 29), (28, 10, 0, 29))):
        name = f"poketto-ipam-{run}-{index}"
        network = name + "_default"
        subnet = unused_subnet(docker, prefix)
        address = str(subnet.network_address + offset)
        pool = ipaddress.ip_network(f"{subnet.network_address + pool_offset}/{pool_prefix}")
        env = dict(environment, POKETTO_NETWORK_SUBNET=str(subnet),
                   POKETTO_NETWORK_DYNAMIC_RANGE=str(pool), POKETTO_GATEWAY_INTERNAL_IP=address)
        raw = json.loads(docker("compose", "--env-file", empty, "-f", root / "deploy/compose.yaml",
                               "config", "--format", "json", env=env).stdout)
        config = {"name": name, "services": {}, "networks": raw["networks"]}
        config["networks"]["default"].pop("name", None)
        config["networks"]["default"]["labels"] = {label: run}
        for service, production in raw["services"].items():
            # Only service payloads change: retain actual dependencies, endpoint addresses and IPAM.
            config["services"][service] = {
                "image": image, "depends_on": production.get("depends_on", {}),
                "networks": production["networks"], "labels": {label: run},
                "entrypoint": ["python", "-c", "import time; time.sleep(120)"],
                "healthcheck": {"test": ["CMD", "python", "-c", "pass"],
                                "interval": "1s", "timeout": "2s", "retries": 3},
                "mem_limit": "48m", "cpus": "0.25", "pids_limit": 16,
                "read_only": True, "cap_drop": ["ALL"], "security_opt": ["no-new-privileges:true"],
            }
        path = output / (name + ".json")
        path.write_text(json.dumps(config), encoding="utf-8")
        record = {"subnet": str(subnet), "pool": str(pool), "gateway": address, "services": {}}
        try:
            docker("compose", "--env-file", empty, "-f", path, "up", "-d", "--wait",
                   "--wait-timeout", "30", "--pull", "never", timeout=45)
            ipam = json.loads(docker("network", "inspect", network).stdout)[0]["IPAM"]["Config"][0]
            assert ipam["Subnet"] == str(subnet) and ipam["IPRange"] == str(pool), ipam
            ids = docker("ps", "-aq", "--filter", f"label=com.docker.compose.project={name}").stdout.split()
            for container in ids:
                info = json.loads(docker("inspect", container).stdout)[0]
                assert info["Config"]["Labels"].get(label) == run
                service = info["Config"]["Labels"]["com.docker.compose.service"]
                actual = info["NetworkSettings"]["Networks"][network]["IPAddress"]
                assert info["State"]["Status"] == "running", info["State"]
                if service == "gateway":
                    assert actual == address
                else:
                    assert ipaddress.ip_address(actual) in pool
                record["services"][service] = actual
            assert set(record["services"]) == {"db", "app", "frontend", "gateway"}
            assert len(set(record["services"].values())) == 4
            record["status"] = "PASS"
            print(f"PASS actual Compose allocation gateway .{offset}, /{prefix}, pool /{pool_prefix}", flush=True)
        finally:
            # Cleanup remains available after the enclosing gate deadline or an interrupted request.
            def cleanup_command(*args):
                result = subprocess.run(["docker", *args], text=True, capture_output=True, timeout=30)
                if result.returncode:
                    raise RuntimeError("IPAM cleanup failed: " + result.stderr[:600])
                return result.stdout

            ids = cleanup_command("ps", "-aq", "--filter", f"label=com.docker.compose.project={name}").split()
            for container in ids:
                info = json.loads(cleanup_command("inspect", container))[0]
                assert info["Config"]["Labels"].get(label) == run, "refuse unowned container cleanup"
                cleanup_command("rm", "-f", container)
            networks = cleanup_command("network", "ls", "-q", "--filter", f"name=^{network}$").split()
            for identifier in networks:
                info = json.loads(cleanup_command("network", "inspect", identifier))[0]
                assert info["Name"] == network and info["Labels"].get(label) == run
                cleanup_command("network", "rm", identifier)
            assert not cleanup_command("ps", "-aq", "--filter", f"label=com.docker.compose.project={name}").strip()
            assert not cleanup_command("network", "ls", "-q", "--filter", f"name=^{network}$").strip()
            record["cleanup"] = "PASS"
            results.append(record)
            (output / "ipam-evidence.json").write_text(json.dumps(results, indent=2), encoding="utf-8")
    return results
