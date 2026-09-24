# Search Parsing Cost and Metadata Filtering

Date: 2026-09-23
Status: Implemented

## Measurement and decision

The shared [DocumentSearch matcher](../../src/main/java/io/github/core607/poketto/content/DocumentSearch.java)
parses reading text for each title miss in a nonempty query. Returned snippets
parse the selected documents again. Repeated searches repeat this work against
unchanged source; the public snapshot does not retain extracted reading text.

Tag and inclusive date predicates run before title/body matching. Documents
excluded by metadata therefore never invoke reading-text parsing. Valid query
results, snippets, ordering, counts, pagination, authorization and publication
rechecks retain their existing behavior. Remaining documents still parse on demand.

The baseline at commit `0973963bbf26b667234dc83954f5eed96c189444` evaluated
metadata after body matching. The same synthetic replay before and after the
predicate reorder produced these medians:

| Documents × characters | Query | Before ms | After ms | Before allocated MiB | After allocated MiB |
|---|---|---:|---:|---:|---:|
| 100 × 4,096 | Body hit | 11.972 | 11.903 | 62.043 | 62.043 |
| 100 × 4,096 | No hit | 11.914 | 11.824 | 59.084 | 59.084 |
| 100 × 4,096 | Tag excludes all | 11.494 | 0.021 | 59.084 | 0.003 |
| 1,000 × 4,096 | Body hit | 117.238 | 117.677 | 602.675 | 602.675 |
| 1,000 × 4,096 | No hit | 114.887 | 114.197 | 590.836 | 590.836 |
| 1,000 × 4,096 | Tag excludes all | 114.945 | 0.016 | 590.836 | 0.023 |
| 1,000 × 32,768 | Body hit | 1,026.079 | 1,031.365 | 4,792.971 | 4,792.971 |
| 1,000 × 32,768 | No hit | 1,012.329 | 1,012.535 | 4,698.969 | 4,698.969 |
| 1,000 × 32,768 | Tag excludes all | 1,013.480 | 0.006 | 4,698.969 | 0.023 |

The change removes excluded-document parsing; it does not accelerate an
unfiltered corpus scan. Allocations are cumulative temporary objects created
during one request, not retained heap. Timings cover the shared matcher and
selected snippets, not HTTP, Git, authorization, catalogue traversal, sorting or
production traffic. Sub-millisecond filtered results are near the measurement
floor and are not a service latency promise.

## Replay method

[SearchParsingProbe](../../src/test/java/io/github/core607/poketto/content/SearchParsingProbe.java)
is an explicit test-runtime program, not a timed CI test. It generates ASCII
Markdown in which one document in twenty contains the body query and no title
matches, warms the JVM, and reports median and maximum wall time, current-thread
CPU time, allocated bytes and a match checksum. Its control scans pre-extracted
strings: the unfiltered 1,000-document controls took about 0.22–0.24 ms at 4,096
characters and 1.78–1.94 ms at 32,768 characters, a diagnostic lower bound that
excludes projection construction, retained storage, invalidation and
authorization.

The measured runtime was the pinned Linux Temurin 26 image of `linuxStorageTest`
with one CPU, 768 MiB container memory, a 256–512 MiB heap and no network, run
from the classes staged by `./gradlew stageLinuxStorageTest`. Compare another
matcher revision with the same probe source; results vary with corpus, JVM and
host, and no latency threshold gates CI.

## Alternatives and scope

A retained reading-text projection could accelerate repeated unfiltered searches,
but needs retained-byte limits, construction admission, workspace/version identity
and eviction policy. The measurement justifies investigating that option; it does
not settle those contracts or prove that current traffic needs a cache. No such
projection is added. Combining snippet and match parsing for page hits is another
option; the dominant measured work is the corpus scan.

The [site search record](2026-09-14-public-site-search.md) retains complete
current-publication search and all capacity and revocation checks. Its site-wide
entrance supplies no tag/date filter, so it receives no speedup from this reorder.
Filtered workspace searches share the changed predicate. The
[repository authoring foundations](2026-09-05-repository-authoring-foundations.md)
retain the shared search bounds. README capabilities and public API contracts do
not change.

## Verification

A valid-Markdown regression in `MarkdownTextTests` verifies zero reading-text parser calls for documents
excluded by tag or by either date bound, while inclusive date boundaries still
match; shared reading-text, repository-reader and public site search tests pass.
