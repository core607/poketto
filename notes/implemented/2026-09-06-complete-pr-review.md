# Complete Pull Request Review Coverage

Date: 2026-09-06
Status: Implemented

## Problem

The [API review workflow](2026-09-01-api-pr-review-ci.md) used one GitHub diff response and truncated its prefix. GitHub refuses sufficiently large diff responses, and a successful response could still lose most changed files before reaching the model. A green job did not distinguish a complete review from skipped or partial coverage.

## Decision

Keep the configured provider, model, reasoning effort, persona, owner-only admission, and fixed `COMMENT` review event. Replace the transport and completion rules with a trusted Python standard-library runner. This record supersedes the earlier single-request, truncation, warning-only failure, and base-branch rule-loading decisions. Model findings remain advisory; successful coverage is neither approval nor proof that the change is correct.

The workflow and runner come from `main`, including for stacked pull requests targeting `codex/phase-one-*`. Pull request content never supplies executable code or review instructions. A temporary bare repository fetches fixed base/head objects from the canonical GitHub repository and computes their merge-base diff with external diff, text conversion, hooks, and submodule recursion disabled. No head working tree is created. Missing history or unsupported content fails explicitly.

The runner partitions the complete diff at file and hunk boundaries, continuing oversized hunks only at complete UTF-8 lines. Context repeated for a continuation is separate from the recorded raw byte range. The manifest records base, head, merge base, rules hash, complete diff hash, and each part's contiguous range, hash, request size, and review result. Concatenating the retained raw parts reproduces the original diff exactly.

Each serialized model request, including rules, title, context, and JSON escaping, is at most 200,000 bytes. The complete diff is at most 8,000,000 bytes, with at most 32 serial part requests and one cross-contract request. The runner has a 30-minute wall-clock deadline; the workflow allows 35 minutes for checkout and retained evidence. Responses are bounded by 2,000,000 bytes and 50,000 visible characters, with the existing 64,000-token model ceiling. A binary diff, non-UTF-8 text, an oversized indivisible line, or an oversized cross-contract request fails without dropping content. These are explicit limits, not automatic review exemptions.

Every successful part produces a commit-bound review and retains its exact visible text. A final request examines the collected findings and contract relationships; it does not claim to reread source absent from those reports. All part comments remain actionable even when the persona's final summary selects at most three findings. Diff parts, visible responses, and coverage manifest are retained as an Actions artifact for 14 days; provider reasoning and response envelopes are not retained. Mention replacement applies only to GitHub comments.

Missing credentials, failed requests, malformed or truncated model results, missing parts, posting failures, and base/head drift leave the run incomplete and failing. Already posted comments remain bound to their original commit. Complete coverage requires every part and the cross-contract response, successful posting, and an unchanged PR identity. The workflow does not make itself a protected-branch required check or approve or merge anything.

Opening, synchronizing, reopening, marking ready, and changing the target branch trigger review. Editing only the title or description does not. A manual dispatch on `main` can review an existing owner PR; the runner rechecks current admission and base/head identity. Repeating a failed run consumes a new bounded set of provider calls; there is no automatic retry of an ambiguous provider request or GitHub post.

## Alternatives and Consequences

Raising a single response limit cannot remove GitHub's server-side diff limit and makes omission harder to see. Splitting product delivery remains useful for dependency and release review, but even a coherent slice may contain a large lockfile or test fixture. Complete bounded review therefore remains necessary for each slice.

The files API is useful as an inventory, but a missing patch is not complete source coverage. Reading Git objects avoids depending on that response shape while preserving a data-only trust boundary. The runner still sends source diffs and trusted review rules to the configured external provider. Multiple requests cost more than a truncated prefix; the fixed request count and time bounds limit each run.

## Verification

`python3 -m unittest discover -s .github/review -p 'test_*.py' -v` runs in CI. Real Git fixtures exceed 20,000 lines and 200,000 bytes, contain multibyte text and malicious head scripts, and verify byte-for-byte coverage without executing head code. Tests also cover continuation limits, provider failures and truncated results, missing parts, manual-trigger admission, commit binding, and base/head drift.

`repoCheck` and `git diff --check` cover repository form. A live run after the trusted workflow reaches `main` remains necessary to verify provider availability, GitHub posting, and complete coverage artifacts; local fixtures do not claim those external operations succeeded.
