# Sandcrab integration

Poketto uses an independently installed Sandcrab service for repository commands
and a separate service for games. This directory contains consumer configuration,
tool preparation, the release pin and offline migration support. The native API,
daemon, SDK conformance, generic evaluations and density benchmarks belong to the
Sandcrab distribution.

## Release

[sandcrab-release.properties](sandcrab-release.properties) binds the release ZIP
and executable by SHA-256. Obtain that exact ZIP through the operator's authorized
distribution channel. For native acceptance, supply it with
`-PsandcrabBundle=/prepared/sandcrab.zip`, or place it at
`GRADLE_USER_HOME/sandcrab/sandcrab-linux-x86_64.zip`. `stageGameWorker` verifies it
and stages Sandcrab, bubblewrap, the corresponding licenses and source, and the
threat model. Missing or empty release materials stop staging.

The application image carries the pin. The [deployment verifier](../deploy/native_repository.py)
checks the independently installed executable, configuration, process and endpoint
before replacing application containers. The operator owns service installation,
upgrades and recovery. [Native repository execution](native-repository.md) owns
profiles, storage, socket permissions and application settings.
[Games](../docs/games.md) owns the separate game service and resource pool.

## Toolkit

Prepare the repository workload tools on the Linux service host. The preparation
script extracts distribution packages into a new directory and supplies `rg`,
`socat`, `which` and a Python alias. It requires apt, dpkg and host Python 3.

```sh
sudo bash executor-service/prepare-tools.sh /prepared/tools
sudo bash executor-service/install-sandbox-tools.sh /prepared/tools
```

The installer validates root ownership and permissions before installing shared
distribution tools for images, HTML, PDF, Office documents and archives. It adds
`awk` and `python` aliases that avoid inaccessible `/etc/alternatives` paths, and
uses [prepare-node.sh](prepare-node.sh) for the pinned Node workload runtime.
Tools are shared read-only by configured profiles. Their package upgrades are an
operator action, separate from application image delivery.

On hosts restricting unprivileged user namespaces, install the consumer's
[AppArmor profile](../deploy/sandcrab.apparmor) and select `sandcrab-service` on the
service unit. Native acceptance loads this profile on its disposable host.

## Retained copies and verification

The [offline importer](native-migration.md) preserves legacy work and journal
receipts under stopped-writer and ownership checks. Resolve deferred records
before native authoring; that guide owns the retirement limits.

[Native acceptance](../executor-native/README.md) runs the compiled Java adapters
against the pinned executable, including Unix peer checks, workspace recovery,
volume transfers, observer faults, offline import, games and authenticated clients.
Its release receipt binds the consumer commit and archive digest. Historical
signed-worker measurements remain in the evidence files with their original scope.
