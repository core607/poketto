# Core Code Review and Persistent PR Sessions

Date: 2026-09-10
Status: Implemented

## Problem

Repeated reviews of a large pull request rebuild the same investigation from an empty conversation. Test-only and documentation-only pushes also consume model calls even though their changes do not require a runtime-code review.

## Decision

The AI review workflow evaluates runtime source, runtime scripts, dependencies, build configuration, deployment configuration and runtime or release workflows. Tests, test-only configuration, documents and static media are excluded from the submitted diff. The reviewer may read tests and documents as supporting evidence, but reports no findings about those files and does not request additional tests or documentation. Deterministic CI remains independent and runs normally.

`.github/review/review_scope.py` owns classification. Git lists changed paths with NUL delimiters and rename detection disabled, then generates a diff using literal selected paths. Moves across the scope boundary therefore retain the runtime addition or deletion. Binary assets outside scope do not prevent review; unsupported binary or non-UTF-8 changes inside scope remain explicit failures. Scope is based on file purpose and location rather than changed-line counts.

The workflow still triggers normally and records `state: exempt` when no core change needs review. It does not post an approval or create a new model review. A prior session is carried forward unchanged, including its actual reviewed head and previous findings. PR title/body edits and draft events retain their no-call behavior.

## Verification before AI review

`AI Review` runs after a completed successful PR `CI` workflow through `workflow_run`; it no longer starts alongside verification on a push. Its executable implementation still comes only from `main`. The runner rechecks the upstream workflow path, repository, PR identity, exact base/head and successful `verify` job before constructing the model provider. Failed, skipped, pending or stale verification cannot authorize a paid call. Manual dispatch also requires successful verification of the current revision and does not fall back to an older success while a matching rerun is pending or failed.

CI also runs when a draft becomes ready or the PR changes its target base. Title/body-only edits skip the verify job. Draft, closed and non-owner PRs are ineligible for AI review even after CI passes. The automatic chain requires exactly one PR association in the upstream run metadata; missing or ambiguous associations produce a no-call result. Normal main publication and deployment do not start a PR review.

## Conversation persistence

Successful reviews upload a separate `ai-review-session-<pr>-<run>-<attempt>` artifact containing `session.json`: repository and PR identity, the reviewed base/head, a model/rule/tool contract fingerprint, exact completed conversation messages, and a versioned audit checkpoint. `.github/review/review_session.py` retrieves artifacts only from successful runs of `ai-review.yml` with matching PR/run names and workflow display titles. Ordinary PR CI artifacts are not session sources. Dispatch runs must use `main`. Artifacts are decoded as bounded data; no archive paths are extracted or executed. The workflow needs `actions: read`, and session artifacts have 90-day retention. Full diagnostic traces remain separate with 14-day retention, so restoring a session does not download the entire trace. A lookup examines at most 500 successful runs; absent or expired state starts a fresh review. Invalid state or a failed required artifact request fails explicitly.

When the base and contract still match, the runner appends the changes since the last actual review to the saved conversation. An intervening test-only push does not advance that baseline. The previous messages, including provider reasoning and tool responses, remain unchanged, and the current revision and new round budget are appended at the end. Current tools read the captured current base/head and return the actual `commit` SHA. Historical results remain evidence about their original revisions, never about the current code by implication. Repeated tool-call detection applies within the current review so rereading an updated file is allowed.

A changed base, model, trusted rules or tool contract starts a fresh context with the prior checkpoint and the complete current core diff. An unavailable historical Git commit also requires complete current core coverage. Manual dispatch requests a fresh full core review with the retained checkpoint. Every posted review remains bound to the captured head and checks for PR drift before and after publication.

## Context rollover

Restored contexts use the provider-measured prompt plus completion token count, with conservative byte-based allowances for appended messages. A large cached transcript is not treated as one token per historical byte. When the context plus the next request cannot fit the 300k input allowance or transport bound, the runner uses the checkpoint instead of replaying old source and reasoning. A multipart review also uses this checkpoint path. It includes every earlier final review and part report, its revision and coverage base, and the tool-read locations used in that review. It does not claim those historical conclusions are still valid. The model must recheck old issues and affected callers while covering all new core changes.

The checkpoint is deterministic and does not require a paid summarization call. It preserves final findings but loses intermediate reasoning and source bodies, so the reviewer may need to retrieve more code after rollover. It is capped at 180k serialized bytes; exceeding that bound fails rather than silently dropping older findings. Session storage is capped at 8 MB, dropping only full transcripts in favor of the complete checkpoint if needed. Input and storage bounds do not introduce a PR-wide output-token allowance. The existing 128k per-call output limit and peak/off-peak round policy remain unchanged.

Provider cache lifetime is not a session lifetime. DeepSeek documents best-effort prefix caching with idle retention usually measured in hours to days, not a guaranteed 24-hour TTL. Preserving the exact prefix enables reuse when available; no cache hit is promised. Persisted conversations also avoid repeating investigations when the provider cache has expired.

## Alternatives and consequences

Whole-workflow path filters can leave required checks pending and cannot compare against the last actual model-reviewed revision. Internal scope selection records an explicit exemption. Changed-line thresholds can exempt a dangerous one-line runtime change, so they are not used. A debounce queue and in-flight handoff add unnecessary machinery to a serial edit/review/fix workflow and are not implemented.

This record narrows the all-file coverage policy in [complete PR review](2026-09-06-complete-pr-review.md) and adds cross-run state to [repository-aware review](2026-09-09-review-agent-loop.md). The [original API review decision](2026-09-01-api-pr-review-ci.md) still owns the advisory, non-required status of AI reviews. Those records retain their independent transport, trust-boundary and tool-loop rationale; none is archived.

Tests use real Git revisions and scripted providers to cover mixed changes, binary asset exclusion, test-only exemptions, append-only continuation, checkpoint rollover, contract/base changes, exact tool revisions, artifact provenance and retained findings. Run `python -X utf8 -m unittest discover -s .github/review -p 'test_*.py' -v`, `./gradlew repoCheck` and `git diff --check`. Live provider cache behavior requires a paid production call; local evidence does not establish its hit rate.
