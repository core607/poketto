# Search Parsing Cost and Metadata Filtering

Date: 2026-09-23
Status: Proposed

## Measurement and decision

The shared `DocumentSearch` matcher parses reading text for every title miss in a
nonempty query. It evaluates tags and dates afterward. Returned snippets parse
the selected documents again. Repeated searches repeat this work against unchanged
source; the public search snapshot does not retain extracted reading text.

A synthetic Linux JDK 26 probe over 1,000 documents of 4,096 characters measured
about 125 ms and 604 MiB of transient allocations per no-hit query. A tag filter
that excludes every document incurred the same cost. At 32,768 characters per
document, each query took about 1.1 seconds and allocated roughly 4.8 GiB. These
are matcher/snippet measurements, not HTTP latency, retained heap, or production
traffic. A pre-extracted string scan is a diagnostic lower bound; it excludes
projection construction and is not an implemented cache.

Evaluate the existing tag and inclusive date predicates before checking title or
parsing body text. This eliminates unnecessary parsing for excluded documents
without changing valid query results, snippets, ordering, counts, pagination,
authorization or publication rechecks. Keep parsing on demand for the remaining
documents. Record comparable before/after measurements with an explicit replay
entrance before claiming a performance improvement.

## Alternatives and scope

A retained reading-text projection could accelerate repeated unfiltered searches,
but needs retained-byte limits, construction admission, workspace/version identity
and eviction policy. The measurement justifies investigating that option; it does
not settle those contracts or prove that current traffic needs a cache. No such
projection is added in this change. Combining snippet and match parsing for page
hits is another independent option; the dominant measured work is the corpus scan.

The [site search record](../implemented/2026-09-14-public-site-search.md) retains
complete current-publication search and all capacity and revocation checks. The
[repository authoring foundations](../implemented/2026-09-05-repository-authoring-foundations.md)
retain the shared search bounds. No public product or API contract changes.

## Verification

Regression coverage must use valid Markdown and prove that excluded metadata does
not invoke reading-text extraction. Retain positive matches and inclusive date
boundaries. Replay shared reading-text, repository search and public search tests;
measure the same synthetic corpus before and after the change without imposing
environment-dependent latency thresholds on CI.
