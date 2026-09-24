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

The `verify` job always runs, always reports under its fixed name, and never reports success for
work its run did not do. Verification runs in parallel lanes chosen by the change classification
([development baseline](2026-08-26-development-baseline.md)); `verify` carries only `if: always()`
and passes only when every lane that classification requires succeeded, so a skipped, failed or
cancelled lane fails it. A title or body edit therefore starts a new verification of a commit that
was already verified, and its result is the truth about that commit rather than a restatement of an
earlier one.

The concurrency group is one per PR number, with branch runs in a separate namespace. Its previous exemption gave a metadata edit its own group
so that a run which did no work could not cancel a real verification; now that every run verifies,
the exemption would instead let two full verifications compete over the same commit, and a flake in
the surplus one would turn the required check red on a commit whose code did not change. An edit's
run now supersedes an unfinished one for the same ref, which costs nothing because both verify the
same commit. A merged PR's `github.ref` can resolve to its base branch, so grouping only by ref lets
a later description edit cancel a main run. Explicit `pr-` and `branch-` prefixes prevent that
collision, including with older PR workflows that still use an unprefixed ref. A main run finishes
once started, because publication and deployment depend on it.

## Alternatives

Skipping the steps while still reporting `verify` as successful is cheaper and was rejected as
unsafe. It reports a conclusion the run did not establish, so a commit whose verification failed,
was cancelled, or is still running would gain a newer green required check as soon as anyone edited
the title. The downstream review gate has the same hole: it admits a run whose job is named `verify`
with conclusion success, which a step-skipping run would satisfy without having verified anything.

Removing `edited` from the trigger list ends the stuck state at no cost, but a retarget would then
never re-verify against its new base. Requiring the branch to be up to date makes a follow-up push
likely rather than certain, and likely is not a gate.

Keeping the skip and relying on maintainers to push an empty commit was the status quo. It costs a
full verification cycle anyway and strands the pull request until someone recognizes the cause.

## Consequences

A title or body edit costs one verification of the change's lanes. Setting the description when the
pull request is opened avoids that cost; editing it afterwards is correct but not free.

## Verification

`.github/review/test_review.py` pins that the job is named exactly `verify`, carries only
`if: always()`, names every lane it waits on in its own decision, and accepts only a successful
lane; a change that reintroduces a skip or leaves an awaited lane unrequired fails it.
