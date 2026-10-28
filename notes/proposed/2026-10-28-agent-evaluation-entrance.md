# Agent Evaluation Entrance

Date: 2026-10-28

## Problem

Poketto's agent-facing surfaces change often: the MCP tool catalog and descriptions ([MCP and isolated execution](../../docs/usage.md#mcp-and-isolated-execution)), the workspace CLI and its help, repository `AGENTS.md` guidance, the creator QA loop ([creator QA](../implemented/2026-10-09-creator-qa.md)) and the game commands. Nothing measures whether a change makes agents succeed more or less often, so each change is judged by impression.

The existing evidence does not answer that question:

- [Client acceptance](../../acceptance/clients/README.md) records that a real Codex or Claude Code process completed a scenario once on one revision. A recovery after reading help is noted by hand. No run repeats a task or compares two revisions.
- The [QA corpus](../../acceptance/qa/README.md) holds five cases. A paid run needs a staged stack, a local key and four commands, and it writes a JSON report.
- The [retrieval laboratory](../../experiments/retrieval-lab/README.md) measures FiQA, an English financial corpus.

Every one of these needs an agent or an engineer to stage a host, run the scripts and read the JSON. The owner cannot change a case or read a result alone.

## Proposal

### One entrance, plain cases

An `evals/` directory holds every suite. The first two are `agent-tasks` and `qa`; games may follow. Each case is one small YAML file that the owner can edit in the GitHub web editor. It contains:

- the task or question, in any language;
- the fixture content it starts from;
- the expected end state.

Expected states use a small fixed vocabulary that the harness checks against authoritative readback. Examples: a file is present, absent or contains a text; an article is public or private; a save produced exactly N commits; an answer cites a given article set with quotes found in the current text; an action is refused. A model judge may grade answer wording only where no exact check exists. It is labelled as such and is never a case's only check.

### Engine

[Inspect](https://inspect.aisi.org.uk/) runs the suites.

- **agent-tasks:** a plain tool-calling agent receives the real MCP tools of a disposable Poketto stack. The real Codex and Claude Code clients that client acceptance already drives can be added as optional agents.
- **qa:** questions go through the product's own QA HTTP entrance, the path `acceptance/qa/run.py` uses, because the product owns that loop. Inspect only schedules the cases and scores them.

Each case runs three times by default, because one run cannot separate a reliable success from a lucky one. Each run records per case:

- the pass rate;
- tool calls and refused or failed calls;
- help reads;
- tokens, cost and duration.

Repeated failures, or repeated help reads at the same step, mark a tool or message to redesign.

### Where it runs

The stack is the disposable Spring, PostgreSQL and sandbox set that [`native-host.py`](../../acceptance/clients/native-host.py) already starts with synthetic accounts and Git authority. It needs the real executor, so the runner must provide cgroup v2, systemd, root, bubblewrap and unprivileged user namespaces ([sandbox verification](../../executor-native/README.md)).

**A run never targets production.** Production holds user content and shared limits, and it cannot run an unmerged branch.

### Triggers and report

- **Triggers:** a manual workflow button with inputs for suite, model, repeat count and Git ref, and a weekly run on `main`. Runs are paid, so pull requests do not trigger them automatically.
- **Keys and spend:** the owner stores model keys as repository secrets. Each run has a spend cap, and the defaults are the inexpensive models the QA corpus already uses.
- **Report:** each run publishes a summary page as the workflow summary and an artifact. The page lists every case with its pass rate and a link to the step-by-step transcript in Inspect's log viewer. It compares the run with the latest `main` run, so a case whose pass rate dropped is listed first.

### Order

1. **Validation.** Confirm that a GitHub-hosted Ubuntu runner can start the executor and the `native-host.py` stack, and that Inspect can use Poketto's Streamable HTTP MCP endpoint with a Bearer key. If the runner cannot host the executor, a self-hosted runner takes its place.
2. **agent-tasks.** At least ten cases covering reads, CLI saves, moves, publication, withdrawal, media upload and a refused cross-space write, plus the button and the report.
3. **qa.** The five fixed corpus cases. The real-article set of [retrieval evaluation](2026-10-19-retrieval-evaluation-and-embedding-gate.md) runs here once written.
4. **Comparison.** The `main` baseline comparison and the weekly run.

## Acceptance criteria

- The owner edits a case and starts a run from the GitHub web interface without local tools, and reads the result without opening JSON.
- `agent-tasks` has at least ten cases, each run at least three times and scored from Git and HTTP readback.
- `qa` contains the five fixed corpus cases.
- A run records the revision, model, repeat count and cost. It respects its spend cap and never contacts production.
- The comparison lists each case whose pass rate fell below the latest `main` run.
- A README under `evals/` documents the case format and the check vocabulary.

## Alternatives

- **Keep one script per feature.** Each new script brings its own format and report. None repeats tasks or compares revisions, which is the gap this entrance closes.
- **promptfoo.** It compares prompts and models over single-turn cases in a table and would host `qa` well. Multi-step tool tasks need a custom provider that owns the agent loop, so the entrance would split across two tools.
- **Hosted evaluation platforms.** Self-hosted Langfuse is a resident service with its own database, which the two-core, 4 GB deployment target cannot spare. Braintrust is a hosted service and would send transcripts to a third party.
- **Grade everything with a model.** Git and HTTP readback give exact results for content tasks. A model judge adds cost and its own variance.

## Consequences

Every run spends model money and runner time, so runs are explicit or weekly. Cases need edits when the CLI or a tool contract changes; such a failure shows that agents are affected too. Fixture content is synthetic, so `agent-tasks` measures tool design rather than the production corpus. Inspect becomes a pinned Python dependency of `evals/` only. The application does not depend on it.
