# Command Timeout and Explicit Copy Disposal

Date: 2026-09-14

## Problem

The executor closes its working copy when a command times out. A slow command
therefore deletes unrelated unsaved files even after its process tree has been
contained. With retained execution disabled, the required generation also makes
the existing discard tool unusable. Explicit new admission correctly refuses to
replace a live copy, leaving no tool for deliberately releasing that copy.

## Decision

A timed-out execution keeps its working copy after the worker confirms that the
command's complete control group is empty and finishes command cleanup. The result
reports timeout and a nonzero exit code with the same copy ID. Earlier edits and
partial output from this command remain local. The next command starts at the
repository root with a fresh temporary directory. Failed initialization, resource
exhaustion, cancellation, revocation and unconfirmed containment retain their
existing closure behavior.

This changes the runtime lifecycle, not the durability guarantee. With retention
disabled, transport expiry, application deployment and worker loss may still
remove unsaved work. With retention enabled, an acknowledged timeout checkpoints
its partial work through the ordinary completion boundary; it is not an unknown
execution outcome merely because the command exceeded its deadline.

`repo_discard` accepts an exact copy ID without a generation for non-retained
copies. The host checks current execution permission and binds lookup to the
credential subject, workspace and copy ID. A busy command refuses disposal.
Successful containment precedes removal of the application binding and release
of admission capacity. An unconfirmed close preserves the binding and capacity
until containment is confirmed; it never enables an overlapping replacement.
Retained copies still require their observed generation and existing writer fence.

`DISCARDED` or `ABSENT` confirms absence of the addressed copy for that owner.
Repeated disposal is safe and never reverses Git commits. A different live copy
in the calling transport still prevents `new`; closing a known copy does not
authorize another identity's copy or imply identity-wide takeover.

## Alternatives and consequences

Allowing `new` to replace a live copy would turn an admission request into implicit
data deletion. Requiring durable recovery just to release a live copy couples
independent capabilities. Explicit disposal and command-level timeout containment
work within existing session limits and require no new backup service. They do
not provide recovery after host or process loss.

## Verification

- The real worker/SRT timeout kills descendants and preserves prior text, binary
  files, partial command work and the pinned commit for a following command.
- The authenticated HTTP MCP entrance accepts generation-free disposal when retention is off,
  repeated disposal and fresh admission; owner boundaries, active-command refusal
  and missing CLOSE acknowledgements preserve isolation and admission fencing.
- Retained execution preserves generation validation and checkpoints an
  acknowledged timeout before allowing later recovery.

The [native probe](../../executor-native/README.md) exercises real process
containment with synthetic authorization. The [HTTP client](../../acceptance/clients/ephemeral-http.py)
uses normal account login and API keys with PostgreSQL and native SRT; it checks
unchanged Git authority through the authenticated file reader. Socket tests inject
lost CLOSE replies and concurrent commands. These checks do not establish the
broader final HTTPS or model-client work-continuity acceptance.

The same-topic audit retains [copy identity](../implemented/2026-09-12-executor-copy-identity.md),
[CodeAct authoring](../implemented/2026-09-09-codeact-content-and-media.md),
[session artifacts](../implemented/2026-09-10-session-artifacts.md) and the broader
[work-continuity proposal](../proposed/2026-09-12-executor-work-continuity.md). This decision
changes timeout and explicit disposal only; durability, scheduling and isolation
work remain separately owned.
