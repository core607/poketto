# Verification Reporting for Metadata Edits

Date: 2026-09-12
Status: Implemented

## Problem

`main` requires the `verify` status check. The CI workflow also listened for the pull-request
`edited` event so that retargeting to a different base re-verifies against it, and it suppressed the
expensive work for a title or body edit two ways at once: a job-level condition skipped the job, and
a conditional job name kept a skipped check from being published as `verify`.

The rename worked, and that was the defect. Branch protection expects the required context from the
newest run of the workflow, and a metadata edit starts such a run. Because that run published a
check under a different name, `verify` was never satisfied for the commit, and the pull request
became permanently unmergeable with `Required status check "verify" is expected`. Re-running the
earlier successful workflow does not clear it; only a new head commit does.

Two pull requests reached that state: one after an unrelated body edit, and one during the
[Java style baseline](2026-09-12-java-style-baseline.md). Each needed an empty commit to recover.

## Decision

The `verify` job always runs and always reports under its fixed name. A job-level `METADATA_ONLY`
variable carries the same condition as before, and every step is guarded by it, so a title or body
edit starts a runner, runs nothing, and publishes a successful `verify` for a commit that an earlier
run already verified. Retargeting still has `METADATA_ONLY` false and verifies in full.

The concurrency group is unchanged: a metadata edit already gets its own group keyed by run id, so
it cannot cancel a verification that is still running for the same commit.

## Alternatives

Removing `edited` from the trigger list is the smallest change and would end the problem, but a
retarget would then never re-verify against its new base. That the branch must also be up to date
makes a follow-up push likely rather than certain, and "likely" is not a gate.

Moving the real work into a separate job and leaving `verify` as a gate that reports its result
keeps the steps unguarded, at the cost of an extra job, a second runner, and a hand-written result
translation. The guard is repeated once per step here instead, which keeps the dependency graph and
the publication jobs untouched.

Keeping the skip and relying on maintainers to push an empty commit was the status quo. It costs a
full verification cycle and strands the pull request until someone recognizes the cause.

## Consequences

A title or body edit now starts a runner that does nothing for a few seconds. That is the price of
a required check that always reports.

Every step in the job carries the same guard. A step added without it would run during a metadata
edit, which is visible as unexpected work rather than as a silent gap.

A pull request already stranded by the old behavior is not repaired by this change. It still needs a
new head commit, because the unmet expectation belongs to the commit that was current at the time.

## Verification

- The workflow's own pull request runs `verify` to success on push.
- Editing that pull request's body afterwards starts a second run whose steps are all skipped and
  which still publishes `verify` as successful, leaving the pull request mergeable.
