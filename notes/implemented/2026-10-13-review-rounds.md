# Review Rounds and Review Output

Date: 2026-10-13

## Problem

The AI review is advisory, but its reader is usually the agent that wrote the pull request, and that agent acts on every finding: a blocker is always fixed and a suggestion usually is. Each action is a push, a five-minute CI run and another review. Across the 95 owner pull requests reviewed between 2026-09-12 and 2026-10-13, a pull request averaged 2.4 reviewed heads. Of 133 transitions to another round, 104 were driven by adopting suggestions and 57 by suggestions alone; 37 fixed a blocker, and 15 only merged `main`. Sixty-seven of the 95 pull requests never had a blocker.

The blockers earned their cost: about four in five of the 50 were real, and 38 were fixed. The rest of the output did not. Of 354 suggestions, about one in seven was refuted by the author's test or source evidence; 53 findings repeated a point the author had already answered, because a re-review saw its own earlier reviews but never the replies; 18 concerned code the pull request did not change, including code that arrived from `main`; and the persona the [original review decision](2026-09-01-api-pr-review-ci.md) chose padded most reviews, clean rounds most of all, under a required length of 300 to 1,000 characters.

A title or description edit also restarted CI. `edited` was a trigger, the run matrix ran in full, and the pull request concurrency group cancelled the run already verifying the same head.

## Decision

The system prompt lives in [prompt.md](../../.github/review/prompt.md) beside the review code and is loaded from the trusted `main` checkout with it. It has no persona. It tells the reviewer what each finding costs and who reads it, limits review to the pull request's own change and the callers whose behavior that change alters, and sets a bar for a blocker: a concrete trigger, a substantive consequence, and a path the reviewer read in fixed-commit source. A finding short of that bar is listed as unverified. Suggestions are capped at two and are not raised on re-review. Output follows a fixed Chinese template: a one-line verdict, numbered blockers, suggestions, unverified items, a re-review check of earlier blockers and residual risk, each item headed by a plain sentence that a reader who did not write the code can follow. Numbers carry across rounds, and a trailing HTML comment lists the findings as JSON.

A re-review receives the owner's pull request comments posted after the workflow's latest review, as untrusted data: the newest that fit within 24 KB, in posting order. The prompt does not let a declined finding return without new evidence, or change a finding's severity without new facts.

Before posting, the workflow audits the findings list: a missing or malformed list, a file absent at the head, a line beyond the file, and a blocker in a file the pull request did not change are appended as an automatic note under the review. The audit never rewrites the model's text and never fails the run.

When the merge base moves between reviews, by a merge of `main` or a new base, the workflow compares the stable patch identity of the pull request's own core change before and after. An unchanged identity means only `main` moved: no model call is made, the head receives the `ai-review` status with that reason, and the previous session carries forward. A changed identity reviews the complete core diff from the new merge base with the prior checkpoint, never a diff from the previous head, which would contain `main`'s changes.

CI no longer runs on `edited`.

## Alternatives

**Keep the persona and cap its length.** A shorter monologue still occupies the reader on every clean round and still asks the model to invent content when it finds nothing. The persona was chosen for entertainment when a person was the expected reader; that reader is now rarely the one acting.

**Run the review alongside CI instead of after it.** It would save the review's one to five minutes per round, but it spends model calls on heads that fail CI and needs a trigger other than `workflow_run`. Once suggestions stop driving rounds and merge-only heads are skipped, the saving is small. The trigger and gate in [core review sessions](2026-09-10-review-scope-and-sessions.md) stand.

**Re-review merge-only heads with the merged diff.** That is what the session continuation did, and it is how `main`'s code reached reviews of pull requests that never touched it.

**Rewrite or drop findings that fail the audit.** The audit cannot tell a wrong path from a correct one the model misnamed, so it annotates and leaves the decision to the reader.

## Consequences

A head that only merged `main` costs a CI run and no review. A semantic conflict between the pull request and `main`'s new code is then left to CI, not to the reviewer; a full re-review on the next real change still covers the whole pull request against the new base.

Replies make declined findings stick, so an author's mistaken refusal also sticks until new evidence appears. The reviewer reads only comments by the repository owner.

Session contracts include the system prompt, so the first review after this change starts a fresh conversation for every open pull request.

Measured figures come from the review history of those 95 pull requests, classified from the reviews, author replies and commits; they describe the reviewer before this change, not a guarantee after it. How many real defects the reviewer misses is not measured.

## Verification

`.github/review/test_rounds.py` pins the merge-only skip, the full review from a moved merge base, the reply bound and carriage, the findings audit, and the absence of `edited` from the CI trigger. The other `.github/review` tests pin that a well-formed review is posted unchanged.
