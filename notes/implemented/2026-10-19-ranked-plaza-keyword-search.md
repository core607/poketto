# Ranked Plaza Keyword Search

Date: 2026-10-19

## Problem

Agents reach public articles through `rumor` and the creator QA `search` tool. Both
used the shared [DocumentSearch](../../src/main/java/io/github/core607/poketto/content/DocumentSearch.java)
matcher: the whole query was one case-sensitive literal substring, and matches were
listed newest first. A query naming two words matched only that exact phrase, and a
common word returned its most relevant article wherever its date put it. An agent
sees ten results per call, and a QA question has 16 tool calls, so it spent calls on
single-word retries and page turns, or stopped before reaching the evidence.

## Decision

[PublicPlazaReads](../../src/main/java/io/github/core607/poketto/plaza/internal/PublicPlazaReads.java)
treats a non-blank query as up to eight keywords separated by Unicode spaces.
[KeywordRanking](../../src/main/java/io/github/core607/poketto/plaza/internal/KeywordRanking.java)
owns the rule:

- Every keyword must occur in the title or the visible text. Letters are folded one
  UTF-16 unit at a time, so offsets stay aligned and snippets cut around the earliest
  keyword under the shared excerpt rule.
- Matches are ordered by BM25 (k1 = 1.2, b = 0.75), with a title occurrence counted
  as three body occurrences, then newest first and by reference. Document frequency,
  document count and average length come from the articles the request searches,
  after its tag filter.
- A query with more than eight distinct keywords is refused as invalid input rather
  than truncated.
- A blank query, `stall` and `mirror` keep the newest-first listing.

Scoring runs over the current verified snapshots inside the existing scan, so the
catalogue bounds, refusal and revalidation rules of the
[plaza foundation](2026-10-09-plaza-foundation.md) are unchanged. Nothing is indexed
or kept between requests. Cards and their snippet parsing are built for the returned
page only.

Website, space and authorized repository search keep the literal, case-sensitive
phrase match and date order of the [public site search](2026-09-14-public-site-search.md),
together with its [highlights](2026-09-14-search-highlights-and-reading-return.md).
Every keyword of a phrase occurs wherever the phrase does, so a `rumor` query of at
most eight words finds every article that website search finds for the same text.
The plaza still constructs `DocumentSearch` for its bounds and tag filter.

An index or embeddings need evidence. A persistent lexical index becomes worth its
synchronization and withdrawal work when scans approach the catalogue bounds or
several application instances must share results. Embeddings become worth a model
dependency when evaluation shows misses that rephrased keywords cannot recover.
[Retrieval evaluation](../proposed/2026-10-19-retrieval-evaluation-and-embedding-gate.md)
proposes how to measure both.

## Alternatives

- **A persistent inverted index or PostgreSQL full-text search.** The [parsing cost
  measurements](2026-09-23-search-parsing-cost.md) put a 100-document scan near 12 ms,
  and the production catalogue holds a few hundred articles. An index would add
  invalidation on every publication change and withdrawal. The [stock
  PostgreSQL](2026-09-05-stock-postgresql.md) record removed the unused zhparser
  extension and requires a new decision for any persistent search.
- **Character bigrams for Chinese.** Bigrams would match keywords that the text
  splits, but they also match unrelated pairs and change what counts as a hit.
  Agents can supply shorter keywords themselves.
- **Any-keyword matching.** Ranking would put the best documents first, but totals
  would count documents sharing one common word, and `refineQuery` would stop meaning
  that narrower keywords exist.
- **Ranking website search too.** Readers browsing results expect dates, and the
  browser highlights one phrase. That change stays a separate product decision.
- **A reranking model now.** In the QA loop the model already reads snippets and
  chooses what to open; a second model would add cost before a measured gap exists.

## Consequences

Agents can combine keywords in one call and reach relevant articles on the first
page. Scores depend on the searched articles, so a publication change can reorder
later pages between calls. Each keyword adds one pass over every searched document;
a query parses every candidate's visible text even when its title already matches,
and the existing time and character bounds still refuse an oversized scan.
Supplementary-plane case pairs are not folded.

## Verification

`KeywordRankingTests` pins splitting, folding, the keyword limit, document frequency
and snippets. `PublicPlazaReadsTests` pins all-keyword matching, title weighting,
relevance order, the newest-first blank query and the website-search superset.
`PlazaQaSourcesTests` keeps the fixed QA corpus plans passing.
