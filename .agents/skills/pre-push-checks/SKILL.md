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

`.github/workflows/ci.yml` owns the authoritative command for each surface: find the job whose paths cover the change and run the command on its `run:` line, without `--no-daemon`. That flag suits a one-off CI runner; locally the Gradle daemon is what keeps repeated runs fast. Do not maintain a second copy of that mapping here.

What the workflow file does not tell you:

- While iterating, `./gradlew test --tests '<class-or-pattern>'` is faster than the job's full command, but a focused test counts as evidence only when it exercises the changed source and would fail for the intended regression. Add adjacent tests for a shared contract; never narrow coverage to skip affected files.
- Java changes also run `./gradlew spotlessApply` and follow the [Java style rules](../../../docs/java-style.md).
- Skill or metadata changes run `./gradlew syncClaudeSkills` before `repoCheck`, or the stub check fails.
- Storage changes need Linux; on Windows the required replay is `./gradlew linuxStorageTest`.
- Executor isolation behavior needs the [native probe](../../../executor-native/README.md); protocol tests do not prove real sandboxing.
- A change to user-visible UI is checked in a real browser against the exact changed tree, confirming the claimed result appears. A presentational change, meaning layout, styling or copy that alters no request, authorization, form submission or stored data, may use `next dev` against fixtures or the public API. Anything else runs the [browser entrance](../../../acceptance/README.md). State in the PR what was exercised and what it showed; do not attach screenshots or build an evidence artifact.

Run a full local rehearsal only when the user requests it, CI is being diagnosed, or the change is so cross-cutting that no narrower set is credible.

## Handle failures

If a relevant check fails before an ordinary push, stop and fix it or report the blocker. Do not push in the hope that CI differs.

An environment-specific failure needs evidence: exact command, failure, platform difference, and the non-platform checks that still passed. Bypassing a hook requires explicit user authorization.

## Push and drive to merge

1. Run the selected evidence once; do not repeat a passing command because a commit or push follows.
2. Commit on a short-lived branch and inspect any files changed by formatting or hooks.
3. Push normally so repository hooks run.
4. Verify the remote branch ref equals local `HEAD`, then open the PR and follow its live CI. Pending checks remain pending.
5. After CI succeeds the AI Review workflow posts as a PR **review**, not a comment: read it with `gh pr view <n> --json reviews`. Fix every blocking item and reply naming the commit. A review with no blocking item is a merge verdict: do not push suggestions onto the reviewed head, because each push costs a CI run and another review. Reply once to each suggestion with the reason when declining it; carry the ones worth taking into a later change.
6. A review belongs to the commit it was posted for, and the workflow marks that commit with the `ai-review` status. Before merging, confirm that status is present on the head you are about to merge:

   ```sh
   gh pr view <n> --json headRefOid --jq .headRefOid
   gh api repos/{owner}/{repo}/commits/<head>/status --jq '.statuses[] | select(.context == "ai-review") | .state'
   ```

   A head the review reached a verdict on carries that status, and its description says whether the head was reviewed or had no core-runtime change to review. An absent status means no verdict for that head, either because the run did not finish or because the gate skipped the pull request, so read the run before merging. Any new commit, including the merge `gh pr update-branch` creates, produces a head the workflow has not seen. Wait for its review rather than merging under the run that is still producing it: a review posted after the merge is rejected as stale, so the model calls are spent and their findings never reach the pull request.

Branch protection does not require an up-to-date branch; merge `main` into the branch only when GitHub reports a conflict, then wait for the rerun. Merging to main redeploys production, and the main CI run that precedes deployment is the integration check.

For an explicitly authorized history rewrite on a non-protected working branch, fetch and record the remote OID, then use `--force-with-lease=<branch>:<observed-oid>`. Raw `--force` is never acceptable. Re-fetch after the push and re-audit review state and CI, because commit-based evidence is now stale.
