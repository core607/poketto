# Manual installation baseline

[readiness.py](readiness.py) measures the manual installation requirements in the
[native service README](../../executor-service/rust/sandcrab/README.md). It installs
a candidate binary and its pinned tools, creates the execution account, writes the
operator's configuration and resource slice, starts the daemon, and runs the README's
Python command. The command must succeed and print `42` through the real sandbox.

This is an automated replay of operator steps, not an installer or a turnkey
quickstart. Its receipt counts each installation command, two acquisition commands,
each manually authored configuration file and any AppArmor policy edit. It records
the individual arguments, outcomes and durations, binary/recipe/README hashes,
distribution, kernel and initial tools. A failed first command retains the attempted
steps and elapsed time. A successful measurement can still fail the readiness target.

The timer covers acquisition of a source archive and prebuilt candidate through the
first sandboxed command. The container runner uses local copies; published-release
downloads are not available in this baseline. Human editing time, source compilation,
measurement-controller bootstrap and distribution image preparation are excluded.
Reported time is therefore a lower bound, not an end-to-end installation promise.

## Fresh distribution replay

The [readiness workflow](../../.github/workflows/sandcrab-readiness.yml) owns the
pinned Ubuntu 22.04, Ubuntu 24.04 and Debian 12 images. Its
[Dockerfile](Dockerfile.readiness) adds only systemd, D-Bus and controller Python.
Each measurement starts a new container without Sandcrab, its toolchain or prior
package indexes. Containers share the host kernel; distribution-specific kernels
and an unprivileged production container configuration are outside this baseline.

Build a fixture image using one of the workflow's pinned `BASE_IMAGE` values, then:

```sh
python3 experiments/sandcrab-density/readiness_container.py \
  --image sandcrab-readiness:fixture --source . \
  --binary build/executor-service/bin/sandcrab \
  --report executor-native/evidence/readiness-NEW-RUN.json
```

[readiness_container.py](readiness_container.py) requires Docker and a built
candidate. It uses a privileged disposable container with a private cgroup namespace,
2 GiB memory, no swap, two CPUs and 2,048 tasks. It does not bind the host cgroup
filesystem. The embedded recipe bounds each operator command and the service in
their own systemd units; temporary wrapper overhead remains in elapsed time.
Use an isolated measurement machine, never a production container or production
installation directory.

The runner owns one random container name and verifies its owner label before
cleanup. It retains the recipe receipt, stops and removes that container, and
records removal. The recipe first contains its processes, checks for surviving
mounts, removes its own service files, account and storage, and restores any host
policy it changed. Installed packages disappear with the disposable container.
Failed installation and cleanup attempts retain their receipts; an existing report
is never overwritten.

## Interpretation

`measurementStatus=COMPLETED` requires a successful first command and verified
cleanup. `readiness.targetPass` separately requires at most three operator commands,
zero manual configuration edits and less than five minutes. The
[lightweight proposal](../../notes/proposed/2026-11-02-sandcrab-lightweight-service.md)
owns those targets. This manual baseline cannot replace the later quickstart gate:
that gate must follow the released README verbatim, including acquisition.
