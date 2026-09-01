# Operational Logging and Diagnostics

Date: 2026-09-01
Status: Proposed

## Problem

No application code emits a log record. `src/main/resources` contains only database migrations, so there is no logging configuration either, and the backend the Spring Boot starters place on the classpath runs at framework defaults that nobody selected.

Meanwhile the constraint has been written five times. The [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md) permits logs to carry an opaque internal workspace identifier but not a private name, content, or credential. The [invitation-only membership proposal](2026-08-27-invitation-only-membership.md) keeps invitation tokens, passwords, and session secrets out of logs. The [off-host backup proposal](2026-08-27-off-host-backup-and-restore.md) keeps remote credentials and recovery material out. The [git durability proposal](2026-08-27-git-durability-modes.md) requires sanitized failure categories and forbids ordinary members from reading remote addresses. The [repository-native retrieval proposal](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md) enumerates what an execution record may retain and excludes raw arguments, output, and repository contents.

Each states what must not appear, for its own feature. None states what must appear, none owns the general rule, and no mechanism enforces any of them. A rule written five times and implemented zero times is a rule the next contributor restates a sixth time.

The behavior that most needs diagnosis is already implemented and silent. A machine write that refuses because the worktree differs from `HEAD`, a journal recovery that resets paths after a crash, and a revision conflict all raise an exception to their caller and leave the operator nothing. Work with no synchronous caller has nowhere to report at all.

The exception messages compound the risk rather than solving it. The requirements direct error text at AI callers and require actionable corrections, so those messages name document paths, colliding paths, revisions, and the absolute content directory. Sending them to an authorized caller is the intended behavior. Writing the same strings to operator-visible storage would persist exactly the content the five rules above exclude, and logging a caught exception whole is the most natural thing an unguided contributor will do.

Operational logs and audit records are also not the same thing, and nothing says so. Audit records are a product surface: workspace-scoped, durable, authorized, and covered by backup. Operational logs serve the person running the instance. They have different readers, retention, and disclosure exposure.

## Proposal

### Ownership and boundary

This note owns the general rule for operational logging. Audit records stay owned by the [requirements](../implemented/2026-08-25-requirements-and-architecture.md) and the feature proposals that define them. A log record never substitutes for an audit row, and an audit row is never copied to the log to make it readable. The existing per-feature statements remain valid as local contracts; this note is where the rule they each repeat lives.

Operational logs are not a product surface, not an API, and not review evidence. The [review skill](../../.agents/skills/review/SKILL.md) requires tests or replayable evidence, and a log line is neither.

### What a record may carry

Permitted: the opaque workspace identifier, the opaque principal identifier, document UUIDs, commit SHAs, revision tokens, operation names, outcome classifications, durations, and bounded counts. These identify an operation without describing it.

Forbidden: document titles, paths, tags, and body text; credentials, API keys, session identifiers, and invitation tokens; remote addresses and repository URLs; personal names and email addresses; and absolute filesystem paths.

A document path is content. `documents/` plus a filename usually restates a title, so paths sit on the forbidden side even though they read like structure rather than data. The document UUID identifies the same document without disclosing it.

### Instrumentation records categories, not messages

Code logs an operation name, a stable outcome category, and permitted identifiers. It does not log an exception's message, because those messages are written for the authorized caller and carry the forbidden fields above. A stack trace and its cause may be logged for a failure the operator must diagnose; the message rendered for the caller is not the log record.

### Levels

`ERROR` marks a condition the operator must act on. `WARN` marks degraded operation that continues. `INFO` marks bounded lifecycle facts: startup, resolved configuration, workspace provisioning, a background worker halting until an explicit retry. `DEBUG` carries per-operation detail and is off by default.

An ordinary successful document write emits nothing above `DEBUG`. A record at `INFO` or higher is either actionable or a bounded lifecycle event; per-operation records at those levels turn the log into a stream nobody reads.

### Correlation

Every record emitted while serving a request or running a background operation carries the workspace identifier and an operation identifier. Gathering the records for one operation is then a field match rather than a search through free text.

### Destination and format

The application writes to standard output and leaves collection to the deployment, which matches the container-based delivery the [continuous delivery proposal](2026-08-26-continuous-delivery.md) describes. Format is configuration: a structured encoding where records are collected, a readable encoding for the [local development environment](2026-09-01-local-development-environment.md). Neither the destination nor the format changes which fields are permitted.

### Enforcement

A privacy rule that only review enforces decays. Tests exercise the paths whose exception messages carry forbidden fields — a revision conflict, a path collision, and a refusal against an unclean repository — capture the emitted records, and assert that no document path, absolute filesystem path, or credential appears. The logging configuration is committed and reviewed rather than inherited from framework defaults.

## Implementation scope and dependencies

This proposal depends on the implemented [development baseline](../implemented/2026-08-26-development-baseline.md), the [workspace boundary](../implemented/2026-08-27-workspace-tenancy.md) for the opaque identifier, and the implemented [document write operations](../implemented/2026-08-29-document-write-operations.md), which are the first behavior to instrument.

The first implementation includes the committed logging configuration, the permitted and forbidden field rule, the level policy, correlation identifiers, instrumentation of the implemented content write and recovery paths, and the tests that assert forbidden values stay out.

It excludes metrics, distributed tracing, a health or actuator surface, log shipping and retention, alerting, audit records, a product-visible diagnostics page, and any change to the error text delivered to callers.

## Alternatives considered

**Continue relying on exceptions reaching the caller.** It needs no configuration and the caller already receives an actionable message. It only works where a synchronous caller exists; journal recovery, background replication, and scheduled work fail with nothing to report to, and those are the failures an operator most needs to see.

**Log liberally now and filter later.** This is the fastest route to diagnosing the current code. Content and credentials that reach a log are disclosed at the moment they are written, and a later filter does not unwrite them. The five existing per-feature rules show the project already decided this exposure is unacceptable.

**Adopt metrics and tracing in the same decision.** One coherent observability decision instead of three. Metrics and tracing need a collector, a store, and a deployment surface that do not exist, and they compete for the two-core target; logging needs a configuration file and works today.

**Record operational diagnostics in the audit tables.** One store, queryable with the rest of the product data, already covered by backup. Audit rows are workspace-scoped, authorized, and retained as a product obligation; operator noise there inflates backups and merges two retention policies that should differ.

**Keep the framework defaults and let the deployment collect standard output.** Nothing to configure, and output is already captured by a container runtime. Defaults choose the level, format, and verbosity, and no default distinguishes a document UUID from a document path.

**State the rule in AGENTS.md instead of a decision record.** It would sit where contributors already read. AGENTS.md holds rules in one to three lines with rationale behind a link, and the permitted and forbidden field lists, the level policy, and the audit boundary do not compress to that without losing the part that changes behavior.

## Acceptance

- No record produced by the implemented content paths contains a document path, title, body text, absolute filesystem path, credential, or token. Tests covering a revision conflict, a path collision, and an unclean repository assert this against captured output.
- Every record emitted during an operation carries the workspace identifier and an operation identifier.
- Logging configuration is committed, and the application does not run on framework defaults.
- A failure with no synchronous caller produces a record naming a stable outcome category.
- A successful document write emits nothing above `DEBUG`, and `INFO` volume stays proportional to lifecycle events rather than to traffic.
- Audit ownership does not move: no audit row is duplicated into the log, and no feature note's audit contract changes.
- Enabling `DEBUG` requires an explicit configuration change rather than being reachable by default.

## Risks

Logging a caught exception whole is the single most likely way to leak content here, because the messages are deliberately descriptive and the habit is widespread. The rule to log categories and identifiers rather than messages is the guard, and it is exactly the rule a contributor in a hurry will skip.

Tests can only assert the paths they exercise. They pin the known-sensitive failures and give no coverage for instrumentation added later, so the field rule still depends on review for new code.

The detail that makes `DEBUG` useful for diagnosis is largely the detail the field rule forbids at rest. Turning `DEBUG` on for a running instance is therefore a deliberate operator act with a stated exposure and a decision to turn it off again, not a default state.

Under-logging is a failure mode as well. A background operation that halts silently is worse than one that reports too much, so the lifecycle events at `INFO` are a floor and not only a ceiling.
