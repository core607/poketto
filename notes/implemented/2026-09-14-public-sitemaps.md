# Canonical public sitemaps

Date: 2026-09-14

## Problem

The sitemap enumerates only the default workspace and links to legacy article
redirects. The root discovery page now spans independent space websites, so this
omits other published spaces. There is no robots.txt discovery entry.

## Decision

Next.js owns XML and robots.txt. Spring provides the enabled public space catalog
and each space's routes and update dates from one currently approved website
snapshot. Neither endpoint reads private content, fetches Git or returns document
bodies, repository paths or image grants.

`/sitemap.xml` is an index linking `/sitemap.xml?site=1` for the root page and
`/sitemap.xml?space={slug}` for each enabled website. A space sitemap includes its
`/s/{slug}` entrance and every approved `/s/{slug}/read/...` page. Missing or
disabled spaces return 404; unavailable snapshots return 503. Each request uses
current publication state and disables response caching.

Catalog enumeration uses pages of 100 and refuses more than 10,000 spaces. It
rechecks the catalog before returning. Each space reads one approved snapshot,
then rechecks its publication settings. A response that exceeds sitemap protocol
limits fails instead of truncating. No sitemap is sampled from discovery batches.

`/robots.txt` allows public crawling, discourages crawling `/admin` and `/api/`,
and advertises the sitemap index at the configured public origin. It provides
crawler guidance; repository publication and website authorization enforce privacy.

## Alternatives and consequences

A single combined URL set would need to fail when the sum of otherwise valid
spaces exceeds 50,000 entries. Per-space files avoid that aggregate limit and
isolate unavailable content to its own sitemap. The index may link an enabled
space whose first verified snapshot is not ready yet; its child returns 503 until
background refresh succeeds.

The [website delivery boundary](../implemented/2026-09-14-workspace-public-delivery.md)
continues to own withdrawal and image invalidation. The
[multiuser plan](2026-09-11-multiuser-workspaces-and-discovery.md) and
[daily-use plan](2026-09-05-phase-one-daily-use.md) retain their remaining scope.
The [frontend boundary](2026-08-30-nextjs-frontend.md) retains Next.js ownership of
presentation resources; this change supplies its multi-space sitemap contract.

## Verification

Real local Spring, PostgreSQL, Next.js and Caddy with two independent spaces
verify the index and child sitemaps, canonical Chinese and ampersand routes,
private-content exclusion, withdrawal through a repository patch, website shutdown
through the owner API, and robots.txt origin and directives. An application outage
returns 503 without a cached partial list. Frontend checks, Java style and
repository validation pass. These checks establish the local serving behavior;
deployment requires a separate production readback.
