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

A head this review reaches a verdict on carries a commit status named `ai-review`, and its
description says which verdict it is: a reviewed head names the pull request its findings were posted
to, and a head with no core-runtime change says there was nothing to review. A reviewed head's status
is written after the review is posted and after the staleness check passes, so it never claims
findings that did not reach the pull request. An absent status means no verdict for that head, which
covers a drifted head, a failed run, a pull request that was already merged, and a pull request the
gate skipped before reviewing it.

A skipped head stays unmarked on purpose. A draft pull request keeps its head commit when it becomes
ready, so marking it would leave a success that outlives the reason for it and claims a review nobody
performed.

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

AGENTS.md carries no sentence saying that no human reads a change before it merges; the
[agent rule surface](2026-09-17-agent-rule-surface.md) change had added one, and this record removed
it. It stated no rule: the authorization rule already tells an agent to drive its own pull request
to merge, so nothing actionable depended on it. It did reach two readers it should not have. The AI
Review workflow loads `AGENTS.md` from `main` as trusted rules, so every review began by being told
that nothing else would catch a defect, which calibrates severity by a story rather than by the
diff. The repository is public, so the same sentence framed the project for any reader before they
saw a line of code. The absence of an approval gate stays recorded in these notes, which is where
rationale belongs.

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

## Consequences

A pull request that falls behind `main` now costs one more review cycle before it can merge, because
the head created by `update-branch` is a head nobody has reviewed. That cycle was already being paid
for and discarded.

A head carries the status only while the review workflow can set it. If the provider is down or the
budget is exhausted, there is none, and the merge decision becomes explicit rather than silent. A run
that finishes can still leave a head unmarked when the gate skipped the pull request, which is a
refusal to review rather than a verdict.

Setting the status never fails the run. The review is public the moment it is posted, and
`review_session.restore` resumes only a successful run, so a run turned red by a failed status call
would invite a re-run that reviews from scratch and posts a second review. The failure is recorded as
`review_status: unset` instead.

That tolerance created a trap, since a successful run is restorable: the same head re-triggers a
review with no new commit, the core diff of that head against itself is empty, and the empty delta
reaches the no-core-change branch. Recording that as "no review needed" for a head whose review is
already posted would have left it permanently unmarked, so the branch recognises an empty delta
against the previous head as "already reviewed" and sets the status, which is idempotent.

## Verification

`.github/review/test_review.py` pins the status rules, the `statuses: write` grant and the
`main`-only identity gate; the CI `java` lane runs it.
