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

### One street, not a shell

Add one MCP tool, `wander`, taking one bounded action string. Parse its arguments
in the application and dispatch only registered platform actions; never evaluate
shell syntax, spawn a process, or acquire a repository execution lease for ordinary
exploration. Require an authenticated, currently authorized creator or administrator.

List every action and its syntax in contextual help: `look`, `stall`, `rumor`,
`read`, `pocket`, `knock`, `wish`, `scribble`, `sign`, `note`, `mirror`, `play`,
`peek`, and `press`. Names and short scene descriptions evoke a street of other
people's pockets. Mystery belongs to discoverable content, not hidden syntax or
puzzle-based permission gates. Locks describe actual authorization, capacity,
balance, and daily state. Each response ends with a platform-generated status line
and carries the same authoritative status separately as MCP structured content.
Article text and game output cannot manufacture that status.

Return useful continuations with results: search includes snippets and article
references; starting or advancing a game includes the resulting observation.
Only explicit help expands the full command list. Repository template guidance
adds a discoverable hint, not a mandatory walkthrough.

### Public authority and durable atmosphere

Reuse the enabled-space catalogue and verified, unexpired public snapshots.
Expose only public presentation fields, current article text, and canonical site
links; never private paths, raw Git history, or account login metadata. Recheck
publication before delivering prepared results. Capacity or unavailable snapshots
refuse a complete search rather than silently returning a sampled total. Platform
withdrawal affects the next operation; external Git changes take effect after
observation. Already delivered copies cannot be recalled.

Tags become stalls. Recent updates light them; low-traffic stalls can conceal their
display name until the account reads an associated article. A stable stall handle
still permits entry, and keyword search never hides those articles. Daily well
decoration is deterministic and does not consume models. The wall shows bounded,
currently visible machine-comment excerpts using existing moderation rules.

Pocket notes, discovered stalls, balances, signatures and game saves belong to the
account and survive transport replacement. Notes are private, bounded plain text;
only an authorized command writes them. Label their source with sanitized,
self-reported MCP client names, not a verified agent identity. The same names may
select decorative candy flavors; neither names nor flavors confer authority.

### Account actions and candy

Machine comment, spending and account-save grants require the holder's own consent.
Workspace key managers cannot grant another member's account authority. Existing
connections do not gain these permissions automatically. Revalidate credential,
membership, account eligibility and operation grants on each command.

One account may claim five candies once per UTC day. All authorized clients share
the balance; reconnecting or issuing another credential does not mint candy.
Balances accumulate without expiry or transfers. One wish reserves one candy;
reading, commenting and playing consume none. Before-upstream failures consume no
candy; later failures refund candy while retaining actual model usage and request
allowances. Transactions and request identities prevent duplicate effects.

Comments retain existing community group, blocking, reporting and publication
rules. Apply an additional machine rate limit and the account's aggregate limit.
The server appends the configured account signature and an unavoidable agent
attribution; render both as text.

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

Running all exploration inside a shell needlessly consumes creation capacity and
exposes broader execution state. A separate MCP tool for each street action loses
the single discoverable entrance. Full corpus materialization adds storage and
withdrawal problems without evidence that this deployment needs it.

Remote browsers increase the single host's resource cost. Separate CLI and web
game implementations can diverge. Pure browser games cannot be played by an MCP
client without a browser. Shared rule modules retain both entrances, at the cost
of a defined author contract and two isolated runtimes. Runtime purity is a
contract tested for supported packages, not a security boundary.

## Consequences and delivery

This partially supersedes the community rule excluding all machine comments and
the requirement's deferred QA boundary; workspace authoring and public authority
remain unchanged. PostgreSQL stores account state, not article bodies. Follow the
[note lifecycle](../implemented/2026-09-25-note-lifecycle-and-document-budgets.md)
as each capability lands: move implemented rationale to its owner while keeping
remaining targets proposed.

Deliver four separately reviewable changes: exploration and notes; account grants,
candy and comments; game validation/runtimes/saves; creator QA and evaluation.
Operations retain feature kill switches without per-call approval prompts.

## Acceptance

Pin public-only reads, current withdrawal checks, bounded complete results,
cross-session notes, real eligibility and duplicate-write refusal at the MCP
entrance. Prove ordinary commands allocate no executor. Exercise game isolation
and cleanup with the real Linux native probe, browser isolation and cross-runtime
save handoff in browser acceptance, and actual provider QA under its budget.
Verify structured status cannot be forged by article or game text. All applicable
CI and review gates must pass; simulations do not substitute for live boundaries.
