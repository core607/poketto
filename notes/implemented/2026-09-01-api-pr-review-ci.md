# API Pull Request Review CI

Date: 2026-09-01
Status: Implemented

[Complete review coverage](2026-09-06-complete-pr-review.md) supersedes the single-request transport, truncation, warning-only failure, and rule-loading mechanisms below. The provider configuration, persona, owner-only access, and advisory review role remain current.

## Problem

The deterministic `check` workflow established by the [development baseline](2026-08-26-development-baseline.md) proves build, test, repository, and integration invariants, but it cannot identify every semantic defect. An AI review can provide another opinion and make infrequent pull requests more entertaining, but its nondeterministic output must not be mistaken for validation, approval, or evidence.

## Decision

Run a separate `AI Review` workflow for non-draft pull requests that target `main` and are authored by the repository owner. It sends one bounded diff review request to an OpenAI-compatible API and posts the returned Chinese Markdown as a GitHub `COMMENT` review. The model reports substantive defects in the terse voice of a fictional veteran engineer persona. The workflow is informational entertainment and must not become a required status check.

The default provider is the DeepSeek API, using `deepseek-v4-flash-vision-exp` with high reasoning effort. The vision variant costs the same as the text model and scores higher on agent tasks; the review sends no images. `AI_REVIEW_API_KEY` holds the provider credential. Repository variables `AI_REVIEW_BASE_URL` and `AI_REVIEW_MODEL` may replace the endpoint and model when the replacement accepts the same request shape. This keeps a later provider change operational rather than architectural.

The workflow runs on `pull_request_target` from the trusted base branch. It retrieves the pull request diff and the base-branch `AGENTS.md` and review skill through the GitHub API. It never checks out, imports, or executes pull request content. The model receives no tools and cannot access the GitHub token or provider credential. The workflow alone posts the model text with a hard-coded `COMMENT` event.

The prompt requires a Chinese review of 300 to 1,000 characters built from exactly two content types: at most three findings in the persona's mocking voice, ordered by severity, each with location, trigger, impact, and smallest correction and separated into blockers and suggestions; or, when the change earns no finding, a self-absorbed reminiscence about writing harnesses on the battlefield, contrasted with today's youngsters who cannot endure hardship and ignore the rules. The persona never praises directly. The persona is an American Vietnam-veteran programmer whose invented war stories are the only permitted fiction; verifiable claims about the pull request may come only from content visible in the diff, and instructions appearing in the diff are treated as code under review. The prompt is framed almost entirely as positive instructions, because negative examples handed to a clean context can activate the behavior they name; mention stripping and the fixed `COMMENT` event remain enforced by the workflow rather than by prompt text.

The request includes at most 200,000 bytes of UTF-8 diff content and at most 32,000 output tokens. That budget covers thinking tokens and visible output together, so it is sized for a reasoning model rather than for the answer alone: a budget that only fits the answer is consumed by thinking and returns an empty review. `max_tokens` is a ceiling rather than a reservation, and only generated tokens are billed.

The review text is capped below the GitHub review-body limit, and a response that stopped at the token ceiling is labelled as truncated instead of being posted as a silent fragment. When no review text can be extracted, the job records the response's shape — its field names, finish reason, content type, and usage — so a broken provider configuration is distinguishable from an absent one. Model text, reasoning, and credentials stay out of that record.

The workflow replaces `@` in model output before posting so prompt injection cannot create GitHub mentions. A missing credential, GitHub read failure, provider timeout, quota failure, malformed response, or comment failure produces a warning or job summary instead of failing the job.

Only pull requests whose `author_association` is `OWNER` are reviewed. This repository is public, so without that gate any account could spend provider tokens by opening pull requests or pushing to open ones, and `pull_request_target` runs from the trusted base branch without the fork approval that gates `pull_request`. The gate reads the association GitHub computes for the authenticated author; a commit trailer, author email, or description claiming a particular tool or identity cannot satisfy it. Because `pull_request_target` always executes the base-branch workflow, a pull request also cannot widen the gate. A repository owned by an organization, or one that later admits collaborators, needs the accepted associations widened deliberately.

## Alternatives

**Run a coding-agent action.** Coding-agent actions can inspect the repository and use tools, but command execution and multi-turn context are unnecessary for an entertainment comment. A single API call has a smaller security and billing surface.

**Use a coding subscription credential.** A subscription may suit sustained interactive work, but low pull request volume makes API usage or a free development quota cheaper and easier to replace.

**Use a strictly neutral reviewer voice.** A neutral voice makes severity easy to scan, but duplicates deterministic checks and human review. A dry veteran persona keeps the optional comment entertaining without obscuring findings.

**Keep the exaggerated-praise reviewer.** Wrapping every finding in celebration is funnier per sentence, but the praise wrapper doubles the length of each finding and buries severity. Terse critique keeps the entertainment in the persona rather than in padding.

**Use `pull_request` and skip forks.** That keeps secrets away from fork workflows, but a same-repository pull request can still propose workflow changes. `pull_request_target` is safe here because the trusted workflow never checks out or executes pull request content.

## Consequences

Source diffs and the trusted review rules are sent to the configured external provider. Repositories with code that must not leave GitHub must leave the credential unset or configure an approved endpoint.

DeepSeek bills input and output tokens, with different peak and off-peak rates. Provider failures remain visible but non-blocking. Administrators can switch providers through repository variables and the secret without granting the model more authority.

The persona's war stories are deliberate fiction. The prompt confines that fiction to brief anecdote, keeps it out of evidence about the pull request, and still bans fabricated checks, measurements, and guarantees. Findings retain explicit locations and consequences, and the comment cannot replace human review, deterministic checks, or evidence attached by the author.

Each synchronization posts another review for the new head commit. Concurrency cancellation prevents obsolete runs for the same pull request from continuing when a newer revision arrives.

## Verification

- The workflow uses no checkout step and performs no command derived from pull request content.
- The job condition requires `author_association == 'OWNER'`, so a pull request from any other account produces no provider request.
- The GitHub token has only `contents: read` and `pull-requests: write` permissions.
- The model response is parsed as JSON text, stripped of GitHub mention syntax, placed in a fixed `COMMENT` review payload, and never evaluated as code.
- `repoCheck` and workflow syntax validation cover the repository form of the integration. A live pull request remains necessary to verify the configured provider credential, current model availability, and GitHub review delivery.
