# API Pull Request Review CI

Date: 2026-09-01
Status: Implemented

## Problem

The deterministic `check` workflow established by the [development baseline](2026-08-26-development-baseline.md) proves build, test, repository, and integration invariants, but it cannot identify every semantic defect. An AI review can provide another opinion and make infrequent pull requests more entertaining, but its nondeterministic output must not be mistaken for validation, approval, or evidence.

## Decision

Run a separate `AI Review` workflow for non-draft pull requests targeting `main`. It sends one bounded diff review request to an OpenAI-compatible API and posts the returned Chinese Markdown as a GitHub `COMMENT` review. The model reports substantive defects while wrapping the review in exaggerated praise. The workflow is informational entertainment and must not become a required status check.

The default provider is the DeepSeek API, using `deepseek-v4-flash` with low reasoning effort. `AI_REVIEW_API_KEY` holds the provider credential. Repository variables `AI_REVIEW_BASE_URL` and `AI_REVIEW_MODEL` may replace the endpoint and model when the replacement accepts the same request shape. This keeps a later provider change operational rather than architectural.

The workflow runs on `pull_request_target` from the trusted base branch. It retrieves the pull request diff and the base-branch `AGENTS.md` and review skill through the GitHub API. It never checks out, imports, or executes pull request content. The model receives no tools and cannot access the GitHub token or provider credential. The workflow alone posts the model text with a hard-coded `COMMENT` event.

The prompt requires a substantive review in exaggerated Chinese praise. Each finding states its location, trigger, impact, and smallest correction direction, but frames the defect as the final polish for an otherwise extraordinary design. It may invent playful titles, awards, metaphors, imagined reactions, and implausibly grand consequences. It must not fabricate executed checks, measurements, security guarantees, requirement completion, or other verifiable facts. It also forbids approval, change requests, and mentions.

The request includes at most 200,000 bytes of UTF-8 diff content and at most 2,000 output tokens. The workflow replaces `@` in model output before posting so prompt injection cannot create GitHub mentions. A missing credential, GitHub read failure, provider timeout, quota failure, malformed response, or comment failure produces a warning or job summary instead of failing the job. Fork pull requests are safe to review because their content remains data throughout the workflow.

## Alternatives

**Run a coding-agent action.** Coding-agent actions can inspect the repository and use tools, but command execution and multi-turn context are unnecessary for an entertainment comment. A single API call has a smaller security and billing surface.

**Use a coding subscription credential.** A subscription may suit sustained interactive work, but low pull request volume makes API usage or a free development quota cheaper and easier to replace.

**Use a neutral reviewer voice.** A neutral voice makes severity easier to scan, but duplicates the tone of deterministic checks and human review. This integration keeps findings explicit while giving the optional comment a deliberately celebratory character.

**Use `pull_request` and skip forks.** That keeps secrets away from fork workflows, but a same-repository pull request can still propose workflow changes. `pull_request_target` is safe here because the trusted workflow never checks out or executes pull request content.

## Consequences

Source diffs and the trusted review rules are sent to the configured external provider. Repositories with code that must not leave GitHub must leave the credential unset or configure an approved endpoint.

DeepSeek bills input and output tokens, with different peak and off-peak rates. Provider failures remain visible but non-blocking. Administrators can switch providers through repository variables and the secret without granting the model more authority.

The celebratory framing can make a defect sound less severe. Findings therefore retain explicit locations and consequences, and the comment cannot replace human review, deterministic checks, or evidence attached by the author.

Each synchronization posts another review for the new head commit. Concurrency cancellation prevents obsolete runs for the same pull request from continuing when a newer revision arrives.

## Verification

- The workflow uses no checkout step and performs no command derived from pull request content.
- The GitHub token has only `contents: read` and `pull-requests: write` permissions.
- The model response is parsed as JSON text, stripped of GitHub mention syntax, placed in a fixed `COMMENT` review payload, and never evaluated as code.
- `repoCheck` and workflow syntax validation cover the repository form of the integration. A live pull request remains necessary to verify the configured provider credential, current model availability, and GitHub review delivery.
