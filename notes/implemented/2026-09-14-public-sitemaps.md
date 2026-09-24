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

`/robots.txt` allows public crawling, discourages crawling `/admin` and `/api/`
and the homepage's discovery batch addresses (`/?batch=` and `/?afterBatch=`),
which every visit mints anew and which only reach pages the sitemaps list,
and advertises the sitemap index at the configured public origin. It provides
crawler guidance; repository publication and website authorization enforce privacy.

## Alternatives and consequences

A single combined URL set would need to fail when the sum of otherwise valid
spaces exceeds 50,000 entries. Per-space files avoid that aggregate limit and
isolate unavailable content to its own sitemap. The index may link an enabled
space whose first verified snapshot is not ready yet; its child returns 503 until
background refresh succeeds.

The [website delivery boundary](2026-09-14-workspace-public-delivery.md) owns
withdrawal, and the [frontend boundary](2026-08-30-nextjs-frontend.md) owns Next.js
presentation resources.

## Verification

`SpacePublicationIntegrationIT` covers the per-space sitemap API over real
PostgreSQL and HTTP; `frontend/tests/api.test.tsx` pins refusal of mixed publication
commits and `frontend/tests/seo-metadata.test.tsx` the robots.txt directives. The
index, canonical Chinese routes, withdrawal and outage behavior were verified once
against a local real stack; no automated test pins the index.
