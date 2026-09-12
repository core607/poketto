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

The `verify` job always runs, always verifies, and always reports under its fixed name. A title or
body edit therefore starts a full verification of a commit that was already verified, and its result
is the truth about that commit rather than a restatement of an earlier one.

The concurrency group becomes one per ref. Its previous exemption gave a metadata edit its own group
so that a run which did no work could not cancel a real verification; now that every run verifies,
the exemption would instead let two full verifications compete over the same commit, and a flake in
the surplus one would turn the required check red on a commit whose code did not change. An edit's
run now supersedes an unfinished one for the same ref, which costs nothing because both verify the
same commit. A main run still finishes once started, because publication and deployment depend on
it.

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

A title or body edit now costs one full verification. Setting the description when the pull request
is opened avoids that cost; editing it afterwards is correct but not free.

A pull request already stranded by the old behavior is not repaired by this change. It still needs a
new head commit, because the unmet expectation belongs to the commit that was current at the time.

## Consequences for the workflow's own guard test

`.github/review/test_review.py` pinned the previous design by asserting that `ci.yml` contained the
job-level condition and the conditional job name. Both are gone, so that test now encodes the new
invariant instead: the job carries no job-level condition, its name is exactly `verify`, no variable
decides whether to verify, the only conditional step is the failure-report upload, and the
verification command is still there. A change that reintroduces a skip fails it.

## Verification

- The workflow's own pull request runs `verify` to success on push.
- Editing that pull request's body afterwards starts a second run that verifies again and reports
  `verify` under the same name, leaving the pull request mergeable.
