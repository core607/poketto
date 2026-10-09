# Public plaza exploration

Date: 2026-10-09

## Problem

The [repository MCP entrance](../implemented/2026-09-10-codeact-mcp-entrance.md)
serves a member's workspace. Agents cannot discover and interact with other
published spaces through that entrance. The [public search](../implemented/2026-09-14-public-site-search.md)
and [community](../implemented/2026-09-23-community-interactions.md) services already
own public reading and human participation, but do not authorize machine comments,
account-owned exploration state, or untrusted games. The independent
[retrieval lab](../implemented/2026-09-24-retrieval-lab.md) does not supply product QA.

## Proposal

The [plaza foundation](../implemented/2026-10-09-plaza-foundation.md) owns the
implemented public-reading entrance, personal pocket consent, notes and discovery
state. The remaining targets below extend that entrance. Help lists unavailable
actions until their capabilities are implemented; it must then reflect current
consent, balances, daily state and operational availability.

The foundation and account-interaction stages are implemented in the
[plaza foundation](../implemented/2026-10-09-plaza-foundation.md) and
[community record](../implemented/2026-09-23-community-interactions.md).
Starting and advancing a game will include its new observation so the caller need
not spend an extra call to see the result.

### A single rule module for games

A public article with a valid unique article ID may reference a versioned package
declaring local resources, help and a JavaScript rule module exporting `init`,
`observe`, and `act`; a presentation component is optional. Inputs and outputs use
bounded serializable state. Random seeds are explicit state, not hidden process
memory. Authors do not maintain separate browser and command-line game programs.

Assign the reserved game marker only after package, path, module and bounded
initialization/observation validation. Ignore an authored marker without valid
capability; preserve source Git bytes and show author diagnostics. Validation is
not a declaration that the code is trusted. Registered platform actions cannot be
overwritten; author actions remain within a selected game.

Web play executes locally in an isolated iframe, with a terminable rule worker,
no login access and no direct platform API authority. Anonymous temporary games
stay in the browser. A narrow save bridge lets logged-in accounts explicitly save
and resume their own games. Browser saves are untrusted and confer no platform
rewards or certified scores. They require bounded data, package-version checks,
optimistic concurrency and idempotency.

Agent play and publication validation use short-lived, independently bounded game
jobs. Reuse worker containment and cleanup infrastructure, not repository copies,
persistent shells or their privileged command bridge. Deploy a separate worker
instance, execution account, admission pool and resource slice. Game jobs see only
their package, state and action; no network, account credential or creative files.
Containment failure never falls back to an ordinary process. Independent admission
does not promise zero shared-host contention: measure mixed workload latency.

An account can explicitly hand the same save between web and agent play. Changed
package versions suspend older saves without automatic migration. Withdrawal
blocks new delivery, cloud-save operations and server execution; it cannot stop
code already downloaded into an offline browser. Ship a step-based puzzle and an
author template. Real-time games and leaderboards are excluded.

### Creator QA and measurement

Implement QA in its Java application module with the configured `deepseek-flash`
provider. Only current creators and administrators may use the web, API or wish
entrance. Configuration enables ordinary operation; there is no extra paid-access
activation ceremony. Default web allowance is five questions per account per day;
candy wishes are separate allowances sharing global cost and concurrency bounds.
One wish reserves one candy. Before-upstream failures consume no candy; later
failures refund candy while retaining actual model usage and request allowances.
Transactions and request identities prevent duplicate effects.

Use the same public search and reading services with a read-only command subset.
Execute multiple tool calls from one model response sequentially and meter each.
Structured clarification waits without model calls or execution leases. Personality
is optional presentation only. Check source existence, exact quoted evidence and
current publication before delivery; report insufficient evidence without claiming
an incomplete search is an exhaustive count.

Reserve conservative model cost before dispatch, with finite input, output,
iteration and time limits. Persist usage and uncertain outcomes before permitting
another charge; do not automatically replay interrupted paid requests. Retain no
question, answer or full trace by default; bounded in-flight text expires on
completion or timeout. Explain upstream processing in the UI.

Initially use existing bounded keyword/tag discovery, not a production embedding
index. Isolate retrieval behind a public-reading service so measured capacity can
later justify an incremental lexical index, and measured semantic misses can
justify embeddings. Neither choice claims agentic superiority. Use a fixed site
regression corpus for retrieval, citation support, latency and cost; paid acceptance
uses the same budget system. Do not launch the FiQA 100-question experiment.

## Alternatives

Remote browsers increase the single host's resource cost. Separate CLI and web
game implementations can diverge. Pure browser games cannot be played by an MCP
client without a browser. Shared rule modules retain both entrances, at the cost
of a defined author contract and two isolated runtimes. Runtime purity is a
contract tested for supported packages, not a security boundary.

## Consequences and delivery

The remaining proposal supersedes the requirement's deferred QA boundary; workspace authoring and public authority
remain unchanged. PostgreSQL stores account state, not article bodies. Follow the
[note lifecycle](../implemented/2026-09-25-note-lifecycle-and-document-budgets.md)
as each capability lands: move implemented rationale to its owner while keeping
remaining targets proposed.

The foundation and account interactions are implemented separately. Deliver the
remaining capabilities in two changes: game validation, runtimes and saves; creator
QA and evaluation.
Operations retain feature kill switches without per-call approval prompts.

## Acceptance

Pin public-only reads, current withdrawal checks, bounded complete results,
cross-session notes, real eligibility and duplicate-write refusal at the MCP
entrance. Prove ordinary commands allocate no executor. Exercise game isolation
and cleanup with the real Linux native probe, browser isolation and cross-runtime
save handoff in browser acceptance, and actual provider QA under its budget.
Verify structured status cannot be forged by article or game text. All applicable
CI and review gates must pass; simulations do not substitute for live boundaries.
