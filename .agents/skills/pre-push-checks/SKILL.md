---
name: pre-push-checks
description: Use when preparing a push or pull request, or assessing delivery readiness. Reporting an individual test result does not require this workflow.
---

# Pre-Push Checks

CI runs the whole matrix on every non-documentation change, so local checks exist to fail fast, not to gate. Run what covers the change, actually run it before saying it passed, and report which commands ran.

## Inspect the outgoing change

Confirm the repository, branch, and worktree state, then read the complete diff against the live PR base or intended target branch, including staged, unstaged, and untracked files. Do not guess the base from a stale local branch, and re-inspect after a base merge or rebase.

```sh
git rev-parse --show-toplevel
git status --short --branch
```

## Select relevant evidence

`.github/workflows/ci.yml` owns the authoritative command for each surface: find the job whose paths cover the change and run the command on its `run:` line. Do not maintain a second copy of that mapping here.

What the workflow file does not tell you:

- While iterating, `./gradlew test --tests '<class-or-pattern>'` is faster than the job's full command, but a focused test counts as evidence only when it exercises the changed source and would fail for the intended regression. Add adjacent tests for a shared contract; never narrow coverage to skip affected files.
- Java changes also run `./gradlew spotlessApply` and follow the [Java style rules](../../../docs/java-style.md).
- Skill or metadata changes run `./gradlew syncClaudeSkills` before `repoCheck`, or the stub check fails.
- Storage changes need Linux; on Windows the required replay is `./gradlew linuxStorageTest`.
- Executor isolation behavior needs the [native probe](../../../executor-native/README.md); protocol tests do not prove real sandboxing.
- A change to user-visible UI runs the [browser entrance](../../../acceptance/README.md) against the exact changed tree and confirms the claimed result appears. State in the PR what was exercised and what it showed; do not attach screenshots or build an evidence artifact.

Run a full local rehearsal only when the user requests it, CI is being diagnosed, or the change is so cross-cutting that no narrower set is credible.

## Handle failures

If a relevant check fails before an ordinary push, stop and fix it or report the blocker. Do not push in the hope that CI differs.

An environment-specific failure needs evidence: exact command, failure, platform difference, and the non-platform checks that still passed. Bypassing a hook requires explicit user authorization.

## Push and drive to merge

1. Run the selected evidence once; do not repeat a passing command because a commit or push follows.
2. Commit on a short-lived branch and inspect any files changed by formatting or hooks.
3. Push normally so repository hooks run.
4. Verify the remote branch ref equals local `HEAD`, then open the PR and follow its live CI. Pending checks remain pending.
5. After CI succeeds the AI Review workflow posts as a PR **review**, not a comment: read it with `gh pr view <n> --json reviews`. Fix every blocking item, take a cheap and clearly correct suggestion, and reply with the reason when declining one.
6. A review belongs to the commit it was posted for, and the workflow marks that commit with the `ai-review` status. Before merging, confirm that status is present on the head you are about to merge:

   ```sh
   gh pr view <n> --json headRefOid --jq .headRefOid
   gh api repos/{owner}/{repo}/commits/<head>/status --jq '.statuses[] | select(.context == "ai-review") | .state'
   ```

   Any new commit, including the merge `gh pr update-branch` creates, produces an unreviewed head. Wait for its review rather than merging under the run that is still producing it: a review posted after the merge is rejected as stale, so the model calls are spent and their findings never reach the pull request.

Branch protection requires an up-to-date branch, so `gh pr update-branch` then wait for the rerun. Merging to main redeploys production.

For an explicitly authorized history rewrite on a non-protected working branch, fetch and record the remote OID, then use `--force-with-lease=<branch>:<observed-oid>`. Raw `--force` is never acceptable. Re-fetch after the push and re-audit review state and CI, because commit-based evidence is now stale.
