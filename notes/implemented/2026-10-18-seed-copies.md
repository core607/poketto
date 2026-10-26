# Seed Copies

Date: 2026-10-18

## Problem

A new full-read [account copy](2026-09-14-account-working-copies.md) exports the whole history as a bundle and clones it inside the sandbox. For a 168 MB repository on the 2 vCPU production host, on 2026-10-17, that took 12.5 s: about 7 s in the application, and 5.2 s with a 204 MB memory peak in the clone. Yet every full-read copy at one commit starts from identical bytes, and [concurrent agent copies](2026-10-18-concurrent-agent-copies.md) need copies to be cheap.

## Decision

The worker keeps seeds, pristine reflinked trees of initialized copies, under `copyRoot/seeds/<workspace>/<scope>/<commit>`.

- **Capture.** A copy initialized from an export is captured once its initializing unit has stopped with an empty control group, before any command runs. The seed holds the bundle and the cloned repository, never identity records, locks or runtime directories. An existing seed is kept. A failed capture only costs a later open its seed.
- **Isolation.** Seeds are mode 0700, owned by the supervisor, and never mounted into a lease. `cp -a --reflink=auto` shares extents on the XFS pool, so a write in a copy allocates its own blocks. Each copy's project quota still counts its full size.
- **Use.** For a full-read copy, the application resolves the commit an export would carry and journals the copy as before. It then sends a seeded `OPEN` that names no export. Without a seed at exactly that commit, the worker refuses with `SEED_ABSENT` before any lease or copy exists, and the application exports that commit and opens as before. Seeds serve every account of the workspace, because a full-read export at one commit is the same for any account allowed to read it. Public copies still export: their projection is built per request.
- **Retention.** The worker keeps the two newest seeds per workspace and scope. An open refreshes a seed's time, and a seed used in the last ten minutes is never pruned. A seed found while an open is validated therefore remains for the copy that uses it. Interrupted captures are removed at startup.

## Alternatives

- **overlayfs over one read-only checkout per commit.** Each copy would depend on a lower layer that must outlive it, and the sandbox's mounts would gain an overlay. Reflinked copies stay ordinary independent directories, so quota, capture, attachment and disposal are unchanged.
- **Advancing an older seed to the requested commit.** This would also serve agents that start after a save moved main, but it needs an incremental export and a checkout inside the sandbox. Exact-commit seeds already cover many agents starting at once. Advancing waits until measurements show the need.

## Consequences

The first copy at each commit still pays the full cost and creates the seed for later ones. Seeds cost metadata on the reflink-enabled pool, plus blocks only where trees differ.

## Verification

`test_seeds.py` pins seed contents, independence from copies, keeping an existing seed, pruning and staging cleanup. `test_worker.py` pins validation of a seeded open and its refusal before any session exists. `WorkerSocketTests.aSeedAtTheResolvedCommitOpensANewCopyWithoutAnExport` pins that no export is made when a seed answers, and that the resolved commit is exported when none does.
