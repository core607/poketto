# The Commit a Review Belongs To

Date: 2026-09-17

## Problem

Two things gate a change: the CI matrix and the AI Review workflow. The matrix is a gate in the
mechanical sense: `verify` is a required status check, and branch protection evaluates it against the
commit being merged. The review was not, because nothing tied its verdict to a commit that a merge
could inspect.

The review is posted with `gh api pulls/<n>/reviews` and carries `commit_id`, but that field is
visible only inside the review object. A merge asks whether the head is mergeable, and the answer
never mentioned whether that head had been reviewed.

The failure this allows is not hypothetical. Branch protection requires an up-to-date branch, so a
pull request that falls behind gets a new head from `gh pr update-branch`, CI reruns, and the review
workflow starts again on the new head. Merging as soon as CI turns green ends the review that is
still running: [review.py](../../.github/review/review.py) re-reads the pull request immediately
before posting and rejects a review whose pull request is no longer open, so the model calls are
spent and the findings are never posted. Six pull requests between #141 and #161 merged that way on
2026-09-17. In each, the head that reached `main` had never been reviewed, and an earlier head had.
For #161 the unreviewed part was not only a merge commit: `e98710b3` removed an accessor.

## Decision

A review that is posted also sets a commit status named `ai-review` on the head it reviewed. The
status is written after the review is posted and after the staleness check passes, so it exists only
for a head whose findings actually reached the pull request. A head that drifted, a run that failed,
and a pull request that was already merged all leave no status, which is the honest answer.

[pre-push-checks](../../.agents/skills/pre-push-checks/SKILL.md) now ends with reading that status on
the exact head being merged, and states that any new commit, including the merge `update-branch`
creates, produces an unreviewed head whose review is worth waiting for.

The workflow gains `statuses: write`. It already had `pull-requests: write`, so this adds no reach
into code or actions.

The review gate also stops accepting a pull request whose base is a `codex/phase-one-` branch. That
prefix belonged to the stacked-branch arrangement of the first delivery, which is
[implemented](2026-09-05-phase-one-daily-use.md); no such base remains, and the exception widened an
authorization check that decides which pull requests receive a paid review with repository read
access. Its guard test now pins the narrower rule.

## Alternatives

**Post the review with `REQUEST_CHANGES` instead of `COMMENT`.** Rejected. Whether a finding blocks
is stated in Chinese prose by the model, not in a field, so the workflow would have to classify
prose to choose an event. It would also change little: `main` requires zero approving reviews, so a
requested change does not prevent a merge.

**Make `ai-review` a required status check.** Not taken here. It would work, and the status this
record adds is what such a rule would name, but it changes branch protection rather than the
repository, and a review outage would then block every merge. The status is published first; making
it required stays a separate decision.

**Have the workflow retry after the pull request is merged.** Rejected. A review that arrives after
the merge cannot stop anything, and posting findings against a closed pull request buys attention
that the deployment has already outrun.

**Treat the merged-under-review case as acceptable because CI still passed.** Rejected. The matrix
proves the change builds and its tests pass; the review is the only reader of intent, lifecycle and
security in the diff. Losing it silently is the whole defect.

## Same-topic audit

[Verification reporting for metadata edits](2026-09-12-metadata-edit-verification.md) owns how the
required `verify` check relates to the newest workflow run, including the cost of the `edited`
trigger; that decision is unchanged and this record does not touch the CI triggers.
[Continuous delivery](2026-09-03-continuous-delivery.md) owns the deployment failure path, including
the deliberate absence of automatic rollback and the manual redeployment of the pins saved in
`.env.previous`; also unchanged.

[Agent rules for a repository no human reviews](2026-09-17-agent-rules-for-an-unreviewed-repository.md)
added a sentence to the arrival guide stating that no human reads a change before it merges. That
sentence is removed here. It stated no rule: the authorization rule already tells an agent to drive
its own pull request to merge, so nothing actionable depended on it. What it did do was reach two
readers it should not have. The AI Review workflow loads `AGENTS.md` from `main` as trusted rules, so
every review began by being told that nothing else would catch a defect, which calibrates severity by
a story rather than by the diff. The repository is public, so the same sentence framed the project
for any reader before they saw a line of code. The absence of an approval gate stays recorded here
and in that note, which is where rationale belongs.

## Consequences

A pull request that falls behind `main` now costs one more review cycle before it can merge, because
the head created by `update-branch` is a head nobody has reviewed. That cycle was already being paid
for and discarded.

A head carries the status only while the review workflow can post it. If the provider is down or the
budget is exhausted, there is no status and the merge decision becomes explicit rather than silent.
A run that exits early also leaves none: a change with no core-runtime files is exempt from review,
and so is a pull request the gate refuses. Those heads are unreviewed by design, not by failure, and
anyone making this status a required check has to decide what such a head should report first.

The status is set after the run's completion record is saved. The review is public the moment it is
posted, so a failed status call must not leave the run reporting a review that nobody can see.

## Verification

- `.github/review/test_review.py` asserts that the status names the same head as the posted review,
  that a drifted head receives neither, that the workflow grants `statuses: write`, and that the
  identity gate accepts only an open owner pull request targeting `main`.
- `python -m unittest discover -s .github/review -p "test_*.py"` passes, which `check` runs as part
  of the `java` lane.
