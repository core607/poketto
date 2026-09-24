# Public view counts

Date: 2026-09-24

## Problem

Authors could not tell whether anyone read an article, and readers had no signal of what others found worth reading. The site sets no analytics and only necessary session cookies, as its [privacy page](../../frontend/app/privacy/page.tsx) states. A counter has to keep that promise and must not give anonymous readers sessions.

## Decision

**What counts.** One view is one browser reading one article on one UTC day.
- [ViewBeacon](../../frontend/components/view-beacon.tsx) reports a reader after the article page has stayed visible for five continuous seconds; hiding the page restarts the wait.
- It then records the day's entry in one `localStorage` value, and does not report that article again the same day. An unreadable store only means an extra request, which the server deduplicates.
- Previews, the editor and server rendering send nothing, so crawlers that do not run script are never counted.

**Endpoints**, in the community controller:
- **Record:** `POST /api/public/community/spaces/{slug}/views?route=…` answers 204 whether or not the view counted, so the response reveals nothing. It carries no body.
  - The browser security chain exempts it from CSRF. It changes no account state, and issuing a token would start a session for every anonymous reader.
  - The Origin check of `OriginAndBodyFilter` still applies.
- **Read:** `GET` on the same address returns `{ "views": n }`, or 404 when the space or route is not public.

**Counting.** [JdbcReadership](../../src/main/java/io/github/core607/poketto/community/internal/JdbcReadership.java) first admits the report by client address: each address gets at most `poketto.community.reader-reports-per-address` reports a day (default 300). This check runs before any snapshot read or write, so rotating user agents cannot force either. It then counts a route only while the route is present in the current website snapshot. It ignores:
- blank user agents and user agents matching common crawler and HTTP-library names;
- routes that do not start with `/` or are longer than 2,048 characters.

**Deduplication without stored identities.** [ReaderDigests](../../src/main/java/io/github/core607/poketto/community/internal/ReaderDigests.java) hashes four parts with SHA-256 and a 32-byte random salt:
- the client address, as the servlet sees it after the deployment's trusted gateway;
- the user agent;
- the space;
- the route.

It keeps the first eight bytes of each digest in memory, together with a per-address report count under the same salt. If storing a count fails, the digest is forgotten and a warning is logged, so a later report from that reader can still count; the endpoint answers 204 either way. At the first view of a new UTC day it replaces the salt and forgets every digest. Once `poketto.community.reader-capacity` digests (default 100,000) are held, further readers that day are not counted. Addresses and digests are never written to the database or logs.

**Client addresses.** The servlet sees the reader's address only when forwarded headers from the gateway are trusted. The [Compose deployment](../../deploy/compose.yaml) sets `SERVER_FORWARDHEADERSSTRATEGY=native` and trusts only the gateway's address. Without that, every reader shares the gateway's address, readers with the same browser build count once a day, and the per-address limit applies to the whole site.

**Storage.** Migration V19 creates `article_views(workspace_id, route, day, views)`, keyed by workspace, logical route and day.
- The route is the key because most existing articles carry no frontmatter `id`, and [community interactions](2026-09-23-community-interactions.md) cannot address such articles.
- Moving an article to another route starts a new count, just as the old address stops resolving.
- Rows survive withdrawal and count again if the same route is published again.

**Display.**
- The reading page reads the total on the server alongside the space information and shows 「阅读 N」 in its meta line when N is at least one. An unavailable total hides the count and nothing else.
- Cards do not show counts.
- The privacy page describes the counter.

## Alternatives

- **Counting on the Next.js server during rendering.** It counts crawlers, prefetches and link previews, and the server sees only the frontend's address.
- **A cookie or persistent visitor ID.** It needs consent handling and changes the privacy promise.
- **Keying by frontmatter `id`.** It would show nothing for current articles until every one is given an ID.
- **An external analytics service.** It sends reader data to a third party and adds script to every page.
- **Storing salted digests in PostgreSQL.** It survives restarts, but it writes a per-reader record that the in-memory set avoids.

## Consequences

- Counts are approximate:
  - A restart replaces the salt, so a reader may be counted again that day.
  - Readers sharing an address and browser build are counted once.
  - Headless crawlers that run script and wait may be counted.
- Rotating addresses inflates counts. The number is a courtesy signal that no behaviour depends on.
- A route reused by a different article inherits the old total.

## Verification

[ReaderDigestsTests](../../src/test/java/io/github/core607/poketto/community/internal/ReaderDigestsTests.java) pins deduplication, salt rotation, capacity and per-address admission; [ReadershipIntegrationIT](../../src/integrationTest/java/io/github/core607/poketto/community/internal/ReadershipIntegrationIT.java) pins counting rules against PostgreSQL; `SpacePublicationIntegrationIT` pins the HTTP entrance without CSRF, session or `Set-Cookie`; and [tests/view-count.test.tsx](../../frontend/tests/view-count.test.tsx) pins the beacon.
