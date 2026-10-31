# Rust Repository Supervisor

Date: 2026-10-21

## Problem

Signed-request, renewal and bridge processing remain material supervisor CPU costs
after synchronization, Java journal and native sandbox-program changes.

## Decision

An independent static Rust service owns repository supervision, using the shared
pinned workspace, safe Unix interfaces and `ed25519-dalek`. It embeds no Python.
The [execution contract](2026-09-05-local-execution-supervisor.md) retains authority,
containment, durability and copy lifetime. The test oracle and baseline installer retain
Python. The [in-repository split](2026-10-27-sandcrab-in-repository-split.md) owns the
shared mechanisms; [pocket games](2026-10-09-pocket-games.md) owns its independent execution scope.

Parsing rejects duplicate keys, preserves numeric token types across shared JSON
features and bounds nesting. Library-version differences may reject malformed
encodings earlier than the oracle; neither implementation admits them.

The supervisor stays outside the sandbox's three-program allowlist.
[Independent delivery](2026-10-31-independent-sandbox-deployment.md) verifies
installed service identity while installation and recovery remain operator-owned.
Obsolete production runtimes are removed after acceptance.

## Alternatives

Python tuning retains measured request overhead. Embedding Python retains its
runtime and divides lifecycle ownership. A native bridge alone leaves signature,
replay and lease processing in the interpreter.

## Consequences

The privileged compiled surface needs independent evidence. `executorRustCheck`
and `executorServiceTests` cover code and differential socket requests and FIFO frames; native
adapter/client fixtures execute the installed binary. Fixture injection is a
separate binary absent from installations. Microbenchmarks establish neither HTTP
latency nor capacity; protected full-window comparisons remain necessary.
