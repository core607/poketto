# Mirror Registry Image Delivery

Date: 2026-09-10
Status: Implemented

Since [PR #119](https://github.com/core607/poketto/pull/119) on 2026-09-14 the existing-layout updater pulls the canonical GHCR images by default; the mirror job and the mirror delivery mode below remain the optional route this record describes.

## Problem

The [continuous-delivery baseline](../implemented/2026-09-03-continuous-delivery.md) streams a `docker save` archive of both images from the GitHub runner to the production host over SSH. The archive is about 265 MB and is resent in full on every deployment, including unchanged base layers. The host sits behind an international link whose throughput collapses during the local afternoon and evening. Deployments on 2026-09-10 measured the route as follows.

| Deployment start (UTC+8) | Result |
|---|---|
| 14:20 | 41 minutes, about 108 KB/s |
| 15:07 | 100 minutes, about 44 KB/s |
| 16:55 | [cancelled at the 180-minute job limit](https://github.com/core607/poketto/actions/runs/34451473847) |
| 20:03 | [cancelled at the 180-minute job limit](https://github.com/core607/poketto/actions/runs/34459071474); 100.8 MB of the archive had arrived, about 9 KB/s |

The `production-deploy` concurrency group serializes deployments without cancellation, so one stalled transfer delays every later `main` merge by hours, and the host stays on the last commit whose archive completed. A longer deadline cannot fix a route that delivers 9 KB/s, and the supplied transfer script does not resume interrupted archives.

## Decision

Publish both images to a second registry inside mainland China and let the host pull them from there. The mechanism is registry-agnostic and configured entirely through GitHub variables and secrets; the operator's chosen registry is a CNB artifact repository (`docker.cnb.cool/<repository>`), which accepts pushes from any Docker client with an access token (username `cnb`) and allows anonymous pulls when the repository is public. GHCR remains the canonical publication; the mirror exists only for delivery.

### Publication

The `publish` job completes canonical GHCR publication before an independent `mirror` job starts. When repository variable `POKETTO_MIRROR_REPOSITORY` is set, `deploy/mirror.sh` copies both published digest references to `<mirror>/poketto:sha-<commit>` and `<mirror>/poketto-frontend:sha-<commit>` with Skopeo `copy --all --preserve-digests`. It does not rebuild the images. The copy must preserve each source digest, and a readback of the destination manifest must hash to that digest before either delivery reference becomes a job output. The workflow summary records both mirror references.

An unset mirror variable skips the job. Mirror login, copy or verification failures cannot invalidate the completed GHCR publication. The deployment configuration gate refuses `mirror` mode unless the mirror job succeeded; `pull` and `transfer` remain usable even when the mirror is unavailable. The mirror job has read-only GitHub package permissions and holds the separate mirror-write credential. Registry logins use standard input and a temporary authentication file deleted at exit.

The copy job runs in the official `quay.io/skopeo/stable` image pinned by digest in the workflow. It has a thirty-minute deadline. The SSH throughput measurements describe the direct server route, not the mirror service's network path; actual mirror duration must be measured before changing this deadline.

Repository configuration: variable `POKETTO_MIRROR_REPOSITORY` (image name prefix, for CNB `docker.cnb.cool/<repository>`), variable `POKETTO_MIRROR_USERNAME` (`cnb` for CNB) and secret `POKETTO_MIRROR_PASSWORD` (a token whose scope covers artifact writes for that repository). The mirror repository holds images built from public source that contain no credentials, so it may be public; a private repository additionally needs the production-environment secret `POKETTO_MIRROR_PULL_PASSWORD` holding a read-only token.

### Deployment

`POKETTO_DEPLOY_MODE=mirror` is valid for both layouts and requires `POKETTO_MIRROR_REPOSITORY`. The deployment job passes the mirror digest references to `transfer.sh --pull`; the host pulls only the layers it lacks. The workflow's configuration check accepts `mirror` in either layout only when the mirror job succeeded.

For the standard layout nothing else changes: `deploy.sh` already pulls digest-pinned images, logs in with streamed `REGISTRY_USERNAME` and `REGISTRY_PASSWORD` lines inside a temporary Docker configuration directory, and verifies revision labels.

For the [existing layout](../implemented/2026-09-08-existing-installation-delivery.md), `transfer.sh` accepts `--existing --pull` for digest references. It sends the trusted `pull-existing.sh` helper through the SSH command and registry credentials through standard input. Both pulls must finish before invoking the installed updater; each registry command has a ten-minute deadline. When supplied, credentials live in a temporary Docker configuration deleted at exit, without using external credential helpers. The selected Docker context is preserved. Existing mode rejects every other settings key before SSH, and the existing-layout step never receives the repository password. The updater resolves the pulled digest references, verifies the revision labels and applies the image-only override without receiving registry credentials.

A failed mirror pull fails the deployment with the pull error. There is no automatic fallback to archive streaming: it would reintroduce the multi-hour slot occupation and hide a mirror outage. The operator restores archive delivery by setting `POKETTO_DEPLOY_MODE=transfer`.

## Alternatives

**Longer deadline or off-peak scheduling.** The [90-minute limit was already raised to 180](../implemented/2026-09-03-continuous-delivery.md); at 9 KB/s the archive needs more than eight hours, and the route also degraded during working hours.

**Host pulls from GHCR.** `--pull` mode exists for the standard layout, but it uses the same international route, the operator had observed unstable GHCR access from the host, and the existing layout did not yet support pulls. The mirror reuses that pull path with a domestic source. A host that reaches GHCR reliably needs no mirror: both layouts now default to pulling from it.

**Tencent Cloud Container Registry.** Equivalent for delivery and interchangeable through the same variables. CNB was chosen because it needs no additional cloud product and its registry accepts pushes from any Docker client; a later switch is configuration, not code.

**Building on CNB.** Removes the cross-border upload entirely, but duplicates verification and would need the verified-`main`-only publication rule reimplemented on a second CI. Deferred unless the mirror push from GitHub proves too slow.

**Smaller or resumable archives.** Better compression, layer-selective saves or `rsync` resume still send from the same runner over the same route and improve throughput at most a few times, not the twenty times needed.

## Consequences and risks

- The upload from GitHub to the mirror still crosses the border, but Docker pushes only layers the mirror lacks. Base and dependency layers are reused across deployments; a typical release uploads the application layers, on the order of tens of megabytes.
- Registry quotas depend on the provider and account plan. Old `sha-` tags accumulate on the mirror and on the host; cleanup of either is separate work.
- A mirror outage blocks deployment in mirror mode, not verification or GHCR publication. Mirror delivery depends on a third-party platform in addition to GitHub.
- Content integrity comes from the expected digest produced by trusted GHCR publication, preserved and checked during copying and used for the host pull. Revision labels are ordinary image metadata and provide only a source-version consistency check; an altered image could retain the same label. A registry that rewrites the manifest cannot satisfy the expected digest.

## Activation and verification

Before selecting `mirror`, create a separate image repository, configure the mirror prefix and write credential, and confirm that a `main` commit has published and copied both images. Never change the visibility of the content repository to enable anonymous image pulls.

`./gradlew deployScriptTests` pins digest preservation, remote manifest readback, the absence of delivery outputs after a failed copy, and the existing-layout pull helper running before the updater. [Production delivery run 34507912175](https://github.com/core607/poketto/actions/runs/34507912175) delivered revision `dd01b4e3` in `mirror` mode with both canonical digests preserved; the copy took 33 seconds and the existing-layout pull, update and health step 42 seconds.
