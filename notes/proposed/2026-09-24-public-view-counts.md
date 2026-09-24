# Public view counts

Date: 2026-09-24
Status: Proposed

## Problem

Authors cannot tell whether anyone reads an article, and readers get no signal of what others found worth reading. The site sets no analytics and only necessary session cookies, as stated on the [privacy page](../../frontend/app/privacy/page.tsx). A counter must keep that promise.

## Proposal

**What is counted.** One view is one browser reading one article on one UTC day.
- The article page sends a beacon after the page has been visible for at least five seconds. The beacon reports the space slug and the logical route.
- The page records the day's article in `localStorage` so it does not send again. A failed storage read only means the server deduplicates instead.
- Nothing is sent for previews, the editor or server-side rendering, so crawlers that do not run script are never counted.

**Endpoint.**
- `POST /api/public/community/spaces/{slug}/views` with body `{ "route": … }`.
- It answers 204 whether or not the view counted, so the response reveals nothing.
- It counts only a route present in the current website snapshot; any other route is ignored.
- It is exempt from CSRF, because it changes no account state and needs no session. Creating a session per anonymous reader would write a row for every visit. The existing Origin check still applies, and the body is capped at 1 KiB.

**Deduplication without storing identities.**
- The server derives a key from the client address the trusted gateway forwards, the User-Agent, the article and the UTC day. It hashes that key with a random salt held only in memory and replaced each day.
- Seen hashes live in a bounded in-memory set that is discarded with the salt. When the set is full, further views that day are not counted.
- A User-Agent matching common crawler names is ignored.
- Neither addresses nor hashes are written to the database or logs.

**Storage.** `article_views(workspace_id, route, day, views)`, keyed by workspace, logical route and day.
- The route is the key because most existing articles carry no frontmatter `id`, and [community interactions](../implemented/2026-09-23-community-interactions.md) cannot address them.
- Moving an article to a new route starts a new count, just as the old address stops resolving.
- Rows survive withdrawal and reappear if the same route is published again.

**Display.**
- The public community article response gains a total `views` field. The article page shows 「阅读 N」 in its meta line once N is at least one.
- Discovery and listing cards do not show counts in this change.

**Privacy page.** It gains a sentence saying that articles count anonymous daily readers without cookies and without storing addresses.

## Alternatives

- **Counting on the Next.js server during rendering.** It counts crawlers, prefetches and link previews, and the server sees only the frontend's address.
- **A cookie or persistent visitor ID.** It needs consent handling and changes the privacy promise.
- **Keying by frontmatter `id`.** It would show nothing for current articles until every one is given an ID.
- **An external analytics service.** It sends reader data to a third party and adds script to every page.

## Consequences and risks

- Counts are approximate:
  - A restart replaces the salt, so a reader may be counted twice that day.
  - Readers behind one NAT address with the same browser build are counted once.
  - Headless crawlers that run script and wait may be counted.
- A client can inflate counts by rotating addresses. The count is a courtesy signal, not a metric anything depends on.
- A route reused by a different article inherits the old total.

## Verification plan

- Endpoint:
  - an unknown route is ignored;
  - duplicate beacons from one client on one day count once;
  - a crawler User-Agent is ignored;
  - no session is created;
  - a wrong Origin is refused.
- The public community article response includes the total.
- Browser test: the beacon fires once after visibility and is not repeated on reload.
