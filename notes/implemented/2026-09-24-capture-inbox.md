# Capture inbox

Date: 2026-09-24

## Problem

Poketto presents itself as a place to record and collect, but adding anything needed the studio editor or an agent session. A link, a quoted passage or a photo met on a phone had no quick way into a space. The [workspace identity](2026-09-06-workspace-identity-http.md) already issues scoped API keys, and the [repository write path](../../src/main/java/io/github/core607/poketto/content/RepositoryPatchService.java) already creates files atomically. The missing piece was a narrow entrance between them.

## Decision

**Capability.** [Capability.CAPTURE](../../src/main/java/io/github/core607/poketto/auth/Capability.java) only creates new Markdown files directly inside `private/inbox/` and uploads their images.
- Private writing implies it. `AuthService` applies the implication only when checking, and reported access keeps the granted set.
- Keys issue it with the usual holder rule, so the holder must currently hold `WRITE_PRIVATE`. A key holding `CAPTURE` alone cannot read, overwrite, move, delete or publish, and cannot create files outside the inbox.
- Migration V20 admits the value in the key capability constraint.

**Write authority.** [JGitRepositoryPatchService](../../src/main/java/io/github/core607/poketto/content/internal/JGitRepositoryPatchService.java) requires `CAPTURE` instead of `WRITE_PRIVATE` for exactly one kind of change: creating an absent file that [CaptureInboxPaths](../../src/main/java/io/github/core607/poketto/content/CaptureInboxPaths.java) accepts, that is a Markdown file directly inside the inbox. Because private writing implies capture, no existing writer loses anything. [AssetService.uploadCaptured](../../src/main/java/io/github/core607/poketto/assets/AssetService.java) stores a capture's image with the same capability.

**Service.** The `capture` module's [RepositoryCaptureInbox](../../src/main/java/io/github/core607/poketto/capture/internal/RepositoryCaptureInbox.java) authorizes `CAPTURE`, then:
1. applies per-account limits: 30 a minute and 500 a UTC day, in memory, with at most 10,000 accounts, so every key and the browser entrance of one holder share one bucket;
2. picks a free name `private/inbox/<UTC YYYY-MM-DD-HHmm>-<title slug>.md` on the fetched head;
3. stores an image if one was sent;
4. writes the note create-only against that head.

A taken name gets `-2` up to `-20`. A remote that moved in between is re-read and retried up to four times before the conflict is reported.

The note holds:
- frontmatter with a fresh `id`, and `title` and `source` written as JSON strings, which YAML reads as double-quoted scalars, so no sent text can break the header;
- `saved`;
- the passage as a block quote, then the note;
- the image as `managed:<asset>:<sha256>`.

URLs must be absolute http or https addresses.

**Entrances.** [CaptureController](../../src/main/java/io/github/core607/poketto/web/internal/CaptureController.java) serves two entrances:
- `POST /api/capture` accepts JSON, or a multipart form with an optional `image`. It has its own stateless Bearer chain modelled on `/mcp`, and a 17 MiB body limit. A multipart body without a declared length is not buffered by the filter; the servlet multipart limits of 16 MB per file and 17 MB per request bound it while parts are parsed. The key's own workspace receives the note. OAuth connection tokens are refused, because their audience is `/mcp`.
- `POST /api/admin/workspaces/{id}/capture` takes JSON with the browser session and CSRF, bounded at 128 KiB. It serves the `/capture` popup.

**Interface.** Owners issue capture-only keys from the space's AI assistant section; members who can write privately get a bookmarklet. The bookmarklet only opens the same-origin popup, because the frontend CSP forbids framing, and opening the popup never writes; only its form saves. Key management offers `CAPTURE` only to holders who can write privately.

## Alternatives

- **Using an ordinary `WRITE_PRIVATE` key in the Shortcut.** A phone-resident key could then overwrite any private file.
- **Calling the API from the bookmarklet's script.** The key would be exposed to the page the bookmarklet runs on.
- **Fetching the linked page server-side to store its text.** That needs the SSRF protections described for visitor Q&A in the [requirements](2026-08-25-requirements-and-architecture.md), and it stores third-party content the member did not select.
- **A distributed signed `.shortcut` file.** Shortcut files must be signed through iCloud sharing, which the server cannot do. Written steps work on every device.
- **Limits in PostgreSQL.** In-memory windows suffice for the single instance. A restart only resets the counts.
- **Reporting `CAPTURE` among a writer's capabilities.** Tests, audit records and the studio read the granted set, so the implication stays a checking rule.

## Consequences

- Each capture is one commit in the member's repository, so heavy collecting grows history. The daily limit bounds it.
- A lost capture key allows appending notes and images to the inbox until revoked. It cannot read or change existing content.
- An image is stored after a free name is found and before its note is written. If the write then fails after its retries, the original stays unreferenced in the space's managed images, where every upload is listed, and the attempt still counts against the limit.
- Managed originals are stored on Linux hosts only. Elsewhere an image capture answers 503 and nothing is written.

## Verification

`AuthIntegrationIT.captureKeysNeedPrivateWritingGrantNothingElseAndWritingImpliesCapture` pins the capability rules; `RepositoryPatchIntegrationIT` pins the capture entrance over real HTTP and Git, including refusal outside the inbox, of overwrites and of nested inbox folders; [RepositoryCaptureInboxTests](../../src/test/java/io/github/core607/poketto/capture/internal/RepositoryCaptureInboxTests.java) and [tests/capture.test.tsx](../../frontend/tests/capture.test.tsx) pin note layout, naming, retries, limits and the interface.
