# Complete Pull Request Review Coverage

Date: 2026-09-06
Status: Implemented

This record owns immutable diff coverage, trusted execution, commit-bound results, and failure rules. [Core review sessions](2026-09-10-review-scope-and-sessions.md) narrows coverage to runtime code, owns the triggers, and adds versioned continuation. [Repository-aware review](2026-09-09-review-agent-loop.md) owns the request and time budgets, the code-reading loop, posting of the final review only, and the diagnostic traces.

## Problem

The [API review workflow](2026-09-01-api-pr-review-ci.md) used one GitHub diff response and truncated its prefix. GitHub refuses sufficiently large diff responses, and a successful response could still lose most changed files before reaching the model. A green job did not distinguish a complete review from skipped or partial coverage.

## Decision

Keep the configured provider, model, reasoning effort, persona, owner-only admission, and fixed `COMMENT` review event. Replace the transport and completion rules with a trusted Python standard-library runner. Model findings remain advisory; successful coverage is neither approval nor proof that the change is correct.

The workflow and runner come from `main`. The gate accepts a `main` base alone since [the reviewed-head status](2026-09-17-reviewed-commit-status.md). Pull request content never supplies executable code or review instructions. A temporary bare repository fetches fixed base/head objects from the canonical GitHub repository and computes their merge-base diff with external diff, text conversion, hooks, and submodule recursion disabled. No head working tree is created. Missing history or unsupported content fails explicitly.

The runner partitions the complete diff at file and hunk boundaries, continuing oversized hunks only at complete UTF-8 lines. Context repeated for a continuation is separate from the recorded raw byte range. The manifest records base, head, merge base, rules hash, complete diff hash, and each part's contiguous range, hash, request size, and review result. Concatenating the retained raw parts reproduces the original diff exactly.

A binary diff, non-UTF-8 text, an oversized indivisible line, or a request that cannot fit its bound fails without dropping content. These are explicit limits, not automatic review exemptions. Missing or provider-truncated text reports the bounded finish reason and visible character count without logging model text. Diff parts, visible responses, and the coverage manifest are retained as an Actions artifact. Mention replacement applies only to GitHub comments.

Missing credentials, failed requests, malformed or truncated model results, missing parts, posting failures, and base/head drift leave the run incomplete and failing. Already posted comments remain bound to their original commit. Complete coverage requires every part and the cross-contract response, successful posting, and an unchanged PR identity. The workflow does not make itself a protected-branch required check or approve or merge anything.

A manual dispatch on `main` can review an existing owner PR; the runner rechecks current admission and base/head identity. Repeating a failed run consumes a new bounded set of provider calls; there is no automatic retry of an ambiguous provider request or GitHub post.

## Alternatives and Consequences

Raising a single response limit cannot remove GitHub's server-side diff limit and makes omission harder to see. Splitting product delivery remains useful for dependency and release review, but even a coherent slice may contain a large lockfile or test fixture. Complete bounded review therefore remains necessary for each slice.

The files API is useful as an inventory, but a missing patch is not complete source coverage. Reading Git objects avoids depending on that response shape while preserving a data-only trust boundary. The runner still sends source diffs and trusted review rules to the configured external provider. Multiple requests cost more than a truncated prefix; the call and time bounds limit each run.

## Verification

`python3 -m unittest discover -s .github/review -p 'test_*.py' -v` runs in the CI `java` lane, with real Git fixtures over 200,000 bytes that contain multibyte text and malicious head scripts.
