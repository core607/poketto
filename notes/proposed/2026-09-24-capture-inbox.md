# Capture inbox

Date: 2026-09-24
Status: Proposed

## Problem

Poketto presents itself as a place to record and collect, but adding anything needs the studio editor or an agent session. A link, a quoted passage or a photo met on a phone has no quick way into a space. The [workspace identity](../implemented/2026-09-06-workspace-identity-http.md) already issues scoped API keys, and the [repository write path](../../src/main/java/io/github/core607/poketto/content/RepositoryPatchService.java) already creates files atomically. The missing piece is a narrow entrance between them.

## Proposal

**Capability.**
- A new key capability, `CAPTURE`, only creates new Markdown files and managed images inside `private/inbox/` of the key's workspace. It cannot read, overwrite, move or publish.
- It can be issued alone, with the same holder rule as other capabilities: the holder must currently hold `WRITE_PRIVATE`.
- Each call intersects the key with the holder's current membership.

**API entrance.**
- `POST /api/capture` accepts a stateless Bearer key through its own filter chain, modelled on the `/mcp` chain in `BrowserSecurityConfiguration`.
- The body is JSON or multipart:
  - text fields: `title`, `url`, `text` and `note`, each bounded;
  - an optional `image` part, up to the managed-image limit of 16 MiB.
- The endpoint answers 201 with the created path.

**Browser entrance.**
- A same-origin `/capture` page opens as a popup, because the frontend CSP forbids framing. It carries `title`, `url` and `text` as query parameters, lets the member choose a space and add a note, and saves with the browser session and CSRF through `POST /api/admin/workspaces/{id}/capture`.
- The bookmarklet only opens that popup, so no key ever reaches a third-party page.
- Opening the page never writes. `SameSite=Lax` sessions accompany GET navigations, so saving happens only on the form's POST.

**File.** The server chooses the path `private/inbox/<YYYY-MM-DD-HHmm>-<slug>.md`, adding a numeric suffix on collision. The file contains:
- frontmatter: a new `id`, `title`, `source` (the URL) and `saved` (the instant);
- the text as a block quote;
- the note;
- an image reference `managed:<assetId>:<sha256>` when an image was sent.

**Write.**
- The write is create-only with `expectedAbsence` against the fetched remote head.
- When the remote advanced, the service re-reads the head and retries up to three times, then answers 409.
- An image upload uses the existing managed store and idempotency key, before the text write.

**Limits.**
- Per key: 30 captures a minute and 500 a day, in a PostgreSQL window like `community_rate_limits`.
- Captures share the existing admission pool for patch and upload bodies.

**Settings.** The space's AI assistant section gains 「收集入口」. It:
- issues a capture-only key, shown once;
- shows step-by-step instructions for an iOS Shortcut that posts the share sheet's URL, text or image with the key;
- offers the bookmarklet to drag to the bookmarks bar.

The owner's agent can later sort `private/inbox/` over MCP.

## Alternatives

- **Using an ordinary `WRITE_PRIVATE` key in the Shortcut.** A phone-resident key could then overwrite any private file.
- **Calling the API from the bookmarklet's script.** The key would be exposed to the page the bookmarklet runs on.
- **Fetching the linked page server-side to store its text.** That needs the SSRF protections described for visitor Q&A in the [requirements](../implemented/2026-08-25-requirements-and-architecture.md), and it stores third-party content the member did not select.
- **A distributed signed `.shortcut` file.** Shortcut files must be signed through iCloud sharing, which the server cannot do. Written steps work on every device.

## Consequences and risks

- Each capture is one commit in the member's repository, so heavy collecting grows history. The daily limit bounds it.
- A lost capture key allows appending notes to the inbox until revoked. It cannot read or change existing content.

## Verification plan

- Capability checks:
  - `CAPTURE` cannot read, overwrite or publish;
  - a path outside the inbox is impossible to request;
  - a revoked key or a narrowed holder is refused.
- Write:
  - a concurrent remote advance is retried;
  - a name collision gets a suffix;
  - limits answer 429.
- The browser entrance refuses a GET save and a missing CSRF token.
- The image and text arrive in one file.
