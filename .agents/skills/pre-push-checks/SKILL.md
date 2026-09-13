---
name: pre-push-checks
description: Use when preparing a push or pull request, or assessing delivery readiness. Reporting an individual test result does not require this workflow.
---

# Pre-Push Checks

One principle: **evidence matches the change surface**. Run the checks that cover what changed; never run the full suite by default — exhaustive coverage and the platform matrix belong to CI. Before claiming that checks pass, actually run them, and report which ones.

## Inspect the outgoing change

1. Confirm the repository, branch, and worktree state:

```sh
git rev-parse --show-toplevel
git status --short --branch
```

2. Verify the live PR base or intended target branch and fetch it. Inspect the complete diff against its merge base, plus staged, unstaged, and untracked files. Do not guess the base from a stale local branch.
3. Read enough context to classify every changed surface. Re-run this inspection after a base merge or rebase because the affected behavior may have changed.

## Select relevant evidence

| Surface | What to run |
|---|---|
| Documents and skills | Run `./gradlew repoCheck` and `git diff --check`; first run `./gradlew syncClaudeSkills` if skill metadata or invocation policy changed. Apply [prose-standard](../prose-standard/SKILL.md) for substantive prose changes and [trim-cot-leakage](../trim-cot-leakage/SKILL.md) when wording depends on an authoring session |
| Code | For an isolated change with demonstrated coverage, run `./gradlew test --tests '<class-or-pattern>'`; use `./gradlew test` for shared unit contracts and `./gradlew check` when build wiring or several runtime surfaces changed. Do not run both focused and full unit tests by default |
| Java | Apply [Java style](../../../docs/java-style.md), including `./gradlew spotlessApply` and Checkstyle for changed source sets, alongside the relevant behavioral checks |
| MCP tools and model-visible output | Run `./gradlew integrationTest --tests '*McpProtocolIntegrationIT'` and affected bounds, cancellation or socket tests. Worker changes also run `./gradlew executorServiceTests`; use the [native probe](../../../executor-native/README.md) for real isolation behavior. Protocol tests do not replace required actual-client acceptance |
| Web UI | Run `./gradlew frontendCheck` plus [ui-evidence](../ui-evidence/SKILL.md) from the exact changed tree. The [browser entrance](../../../acceptance/README.md) runs actual Spring, database and frontend services; `./gradlew stageAcceptanceRuntime` stages application classes and synthetic browser fixtures for that entrance |
| Deployment | Run `./gradlew deployScriptTests` against fake docker, curl, and ssh. Existing-installation update or reconciliation changes also run `./gradlew existingDeploymentTests` on Linux in Docker. Caddy changes run `./gradlew gatewayConfigCheck` against its pinned container; address forwarding or login-bucket changes also run `./gradlew proxyForwardingCheck` with real Caddy/Tomcat and isolated Docker clients. Application image identity or socket wiring changes run `./gradlew appImageIdentityCheck` |
| Database and storage | Run `./gradlew integrationTest` against pinned official PostgreSQL for relational state and real application entry paths. Storage changes also run the owning tests on Linux; Windows uses the required `./gradlew linuxStorageTest` native-disk replay. Include transaction, durability, and concurrency coverage for changed behavior. |

Test selection and coverage selection are separate. A focused test is useful only when it exercises the changed source and would fail for the intended regression. Add adjacent tests for a shared contract; do not narrow coverage merely to hide affected files.

Run a full local rehearsal only when the user requests it, CI is being diagnosed, or the change is so cross-cutting that no narrower set is credible.

## Handle failures and unavailable checks

If a relevant check fails before an ordinary push, stop and fix it or report the blocker. Do not push in the hope that CI differs.

If a required check tool does not exist yet, report the uncovered surface explicitly. Do not skip it silently and do not substitute "it looks fine" or an invented manual pass.

An environment-specific failure needs evidence: exact command, failure, platform difference, and the non-platform checks that still passed. Bypassing a hook requires explicit user authorization.

## Push and verify

Enter this section only when pushing is explicitly authorized. A request to run checks or assess readiness does not authorize a push. Reuse authorization already given for the same push and scope.

1. Run the selected evidence once; do not repeat a passing command solely because a commit or push follows.
2. Commit on a short-lived branch. Inspect any files changed by formatting or hooks.
3. Push normally so repository hooks run. Never push `main` directly.
4. Verify the remote branch ref equals local `HEAD`, then inspect the PR's live CI. Pending checks remain pending.

For an explicitly authorized history rewrite on a non-protected working branch, fetch and record the remote OID, then use `--force-with-lease=<branch>:<observed-oid>`. Raw `--force` is never acceptable. Re-fetch after the push and re-audit review state and CI because old commit-based evidence is stale.
