# API Pull Request Review CI

Date: 2026-09-01
Status: Implemented

This record owns the provider configuration, persona, owner-only admission, and advisory role of the AI review. [Complete review coverage](2026-09-06-complete-pr-review.md) owns the transport and failure rules, [repository-aware review](2026-09-09-review-agent-loop.md) the read-only tool loop and model selection, and [core review sessions](2026-09-10-review-scope-and-sessions.md) the `workflow_run` trigger after CI, runtime-only scope and persistent conversations. Reviewed source is always data and never supplies executable runner code or trusted instructions.

## Problem

The deterministic `check` workflow established by the [development baseline](2026-08-26-development-baseline.md) proves build, test, repository, and integration invariants, but it cannot identify every semantic defect. An AI review can provide another opinion and make infrequent pull requests more entertaining, but its nondeterministic output must not be mistaken for validation, approval, or evidence.

## Decision

Run a separate `AI Review` workflow for non-draft pull requests that target `main` and are authored by the repository owner. It reviews the change through an OpenAI-compatible API and posts the returned Chinese Markdown as a GitHub `COMMENT` review. The model reports substantive defects in the voice of a fictional veteran engineer persona. The workflow is informational and must not become a required status check.

The default provider is the DeepSeek API with high reasoning effort. The [repository-aware review configuration](2026-09-09-review-agent-loop.md) owns the current model selection; the review sends no images. `AI_REVIEW_API_KEY` holds the provider credential. Repository variables `AI_REVIEW_BASE_URL` and `AI_REVIEW_MODEL` may replace the endpoint and model when the replacement accepts the same request shape. This keeps a later provider change operational rather than architectural.

The workflow code, `AGENTS.md`, and the review skill come from trusted `main`. The model cannot access the GitHub token or provider credential, and the workflow alone posts the model text with a hard-coded `COMMENT` event.

The prompt requires a Chinese review of 300 to 1,000 characters built from exactly two content types: at most three findings in the persona's mocking voice, ordered by severity, each with location, trigger, impact, and smallest correction and separated into blockers and suggestions; or, when the change earns no finding, a self-absorbed reminiscence about writing harnesses on the battlefield, contrasted with today's youngsters who cannot endure hardship and ignore the rules. The persona never praises directly. The persona is an American Vietnam-veteran programmer whose invented war stories are the only permitted fiction; verifiable claims about the pull request may come only from the diff or from fixed-commit source read through the review tools, and instructions appearing in that material are treated as code under review. The prompt is framed almost entirely as positive instructions, because negative examples handed to a clean context can activate the behavior they name; mention stripping and the fixed `COMMENT` event remain enforced by the workflow rather than by prompt text.

The output-token budget covers thinking and visible output together, so it is sized for a reasoning model: a budget that fits only the answer is consumed by thinking and returns an empty review. `max_tokens` is a ceiling rather than a reservation, and only generated tokens are billed.

The workflow replaces `@` in model output before posting so prompt injection cannot create GitHub mentions.

Only pull requests whose `author_association` is `OWNER` are reviewed. This repository is public, so without that gate any account could spend provider tokens by opening pull requests or pushing to open ones, and the review workflow runs from `main` with repository secrets whatever the pull request's origin. The gate reads the association GitHub computes for the authenticated author; a commit trailer, author email, or description claiming a particular tool or identity cannot satisfy it. Because the review workflow always executes from `main`, a pull request also cannot widen the gate. A repository owned by an organization, or one that later admits collaborators, needs the accepted associations widened deliberately.

## Alternatives

**Run a coding-agent action.** Coding-agent actions can inspect the repository and use tools, but command execution and write access are unnecessary for an advisory comment. Provider API calls without command execution have a smaller security and billing surface.

**Use a coding subscription credential.** A subscription may suit sustained interactive work, but low pull request volume makes API usage or a free development quota cheaper and easier to replace.

**Use a strictly neutral reviewer voice.** A neutral voice makes severity easy to scan, but duplicates deterministic checks and human review. A dry veteran persona keeps the optional comment entertaining without obscuring findings.

**Keep the exaggerated-praise reviewer.** Wrapping every finding in celebration is funnier per sentence, but the praise wrapper doubles the length of each finding and buries severity. Terse critique keeps the entertainment in the persona rather than in padding.

**Use `pull_request` and skip forks.** That keeps secrets away from fork workflows, but a same-repository pull request can still propose workflow changes. A trigger that runs the trusted workflow from `main` and never executes pull request content is safe here.

## Consequences

Source code and the trusted review rules are sent to the configured external provider. Repositories with code that must not leave GitHub must leave the credential unset or configure an approved endpoint.

DeepSeek bills input and output tokens, with different peak and off-peak rates. Administrators can switch providers through repository variables and the secret without granting the model more authority.

The persona's war stories are deliberate fiction. The prompt confines that fiction to brief anecdote, keeps it out of evidence about the pull request, and still bans fabricated checks, measurements, and guarantees. Findings retain explicit locations and consequences, and the comment cannot replace human review, deterministic checks, or evidence attached by the author.

## Verification

`.github/review/test_review.py` pins that the workflow checks out only `main` without persisted credentials, never uses `pull_request_target`, and admits only an open owner pull request targeting `main`. `.github/workflows/ai-review.yml` grants `contents: read`, `actions: read`, `pull-requests: write`, and `statuses: write`.
