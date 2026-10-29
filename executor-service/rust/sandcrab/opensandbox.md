# OpenSandbox adapter

Set `service.opensandbox = true` with the authenticated loopback TCP listener in
[config.example.toml](config.example.toml). The adapter is disabled by default.
It targets the published OpenSandbox 1.1.0 Python and Kotlin SDKs. The
[independent SDK clients](../../../evals/sdk/README.md) exercise both against
the native daemon. Directory operations, other file APIs and named shell contexts remain under the
[service proposal](../../../notes/proposed/2026-10-26-sandcrab-sandbox-service.md).

## Connection and ownership

Configure the SDK with `protocol="http"`, the literal loopback `host:port` and
the service token as its API key. Lifecycle requests use
`OPEN-SANDBOX-API-KEY`; native requests still use Bearer authentication. Endpoint
lookup returns a distinct `X-EXECD-ACCESS-TOKEN` for each sandbox. The SDK forwards
that header to its execd requests. Endpoint addresses come from the operator's
listener configuration. Deletion, expiry and service restart revoke the scoped
credentials, including when a native client reuses a sandbox's public ID.

The lifecycle key controls the service's sandboxes; it is not a tenant identity.
The transport and host trust requirements are those of [native TCP](README.md#loopback-tcp).
The SDK's Kubernetes `secureAccess` option is unsupported; scoped endpoint
credentials are always required by this adapter.

## Lifecycle

`image.uri` selects an operator profile. The SDK default entrypoint
`["tail", "-f", "/dev/null"]` selects an idle managed shell; it does not launch
that executable. Other entrypoints and image authentication are refused.

Create, get, filtered list, renew and delete use `/v1/sandboxes`. Endpoint lookup
maps ports 44772 and 18080 to path-routed command and read-only egress services.
The returned network policy is the enforced deny-all policy. Custom networking,
images, snapshots, templates, scheduling, credential proxies and volume requests
are refused. Unused snapshot, template, platform, resource-request, lifecycle,
network, credential-proxy and volume options may be omitted or null. Unknown
fields fail.

Supply an explicit timeout between 60 and 86400 seconds within the profile's
session lifetime limit. Renewal sets an absolute expiry and remains bounded by
the profile. Manual cleanup with no expiry is unsupported. Call `kill()` to
delete the remote sandbox and close the SDK client's local resources separately.
Deletion waits for containment; an uncertain cleanup returns an error and keeps
ownership reserved.

Resource limits must include CPU and memory. CPU accepts whole cores, at most two
decimal places or millicores divisible by ten. Memory accepts integer bytes or
`Ki`, `Mi`, `Gi`, `Ti`, `k`, `M`, `G`, `T` suffixes. Requests may lower the profile's
limits within its memory floor and cannot raise them. The SDK defaults can exceed
a small profile, so set limits explicitly.

## Commands

Foreground command strings and literal argument arrays return SDK SSE events.
Each command starts in `/workspace` unless given a literal working directory;
command-specific environment values override creation values. Changes to shell
variables and working directory do not persist between these foreground calls.
Environment expansion in a working-directory option, caller UIDs/GIDs and detached
execution are refused. Native timeout and output limits still apply.

Command status retains at most `service.max_records` outcomes across SDK
sandboxes, evicting completed, unobserved entries first. Native result records are
reclaimed after observation ends. Disconnecting the SSE stream does not replay
or cancel a command. Timeout and explicit cancellation terminate the enclosing
native session, so create another sandbox before executing again.

Output is decoded incrementally as UTF-8; invalid bytes become U+FFFD. Use file
downloads for binary results. An uncertain command outcome emits an error without
a successful completion event. The [native host-call interface](host-calls.md)
has separate result ownership; composing it with SDK command retention is not
yet supported by the adapter.

## Files

SDK uploads accept fixed-length and chunked multipart bodies. Downloads return
fixed-length raw bytes. Paths are relative to `/workspace` or start with
`/workspace/`; traversal, symlink parents and other absolute roots are refused.
Parents must already exist. File ownership remains the execution profile's UID
and GID. SDK decimal octal permissions, such as `640`, select ordinary permission
bits; owner/group overrides and special bits are refused.

A request contains at most 128 files, with aggregate content bounded by the
profile's `transfer_bytes`. Multipart metadata and headers have separate bounds.
Transfers use the native file admission limits. Body ingestion and download
transmission each have a 30-second deadline; helpers and cleanup have separate
bounded deadlines.
The bounded file helper stages the complete request before freezing the sandbox
to validate destinations and publish. A malformed or incomplete body publishes
nothing. Each file replacement is atomic; the entire batch is not a transaction.
An I/O failure during publication may leave a partial batch and returns
`PUBLICATION_UNKNOWN`. Inspect file contents before deciding whether to retry.

Downloads freeze only while making their private snapshot; a slow reader does
not keep the sandbox frozen. Range and line-selection requests are refused.
Closing, expiry and cancellation retain workspace ownership until transfer
helpers and cleanup finish. An uncertain cleanup fails the request and fences
the affected workspace until service recovery.
