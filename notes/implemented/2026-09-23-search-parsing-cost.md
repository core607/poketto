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
is an explicit test-runtime program, not a timed CI test. It creates ASCII
Markdown with headings, emphasis, links, inline code, lists, quotes and GFM tables.
One document in twenty contains the body query; titles do not match. All documents
have the same known tag and date. Each run first warms 1,000 four-KiB documents ten
times, then warms each scenario four times and records nine samples. It reports
median and maximum wall time, median current-thread CPU time and allocated bytes.
It retains a checksum of match count plus returned snippet lengths.

The control scans pre-extracted strings and skips snippet construction. Across
the two runs, the unfiltered 1,000-document controls took about 0.22–0.24 ms at
4,096 characters and 1.78–1.94 ms at 32,768 characters. This isolates the scale of
parsing work but is only a diagnostic lower bound: projection construction,
retained storage, invalidation and authorization costs are excluded.

The measured runtime is the pinned Linux Temurin 26 image, one CPU quota, 768 MiB
container memory and a 256–512 MiB Java heap. The container has no network. Replay
from a Linux checkout with Docker:

```sh
./gradlew stageLinuxStorageTest
docker run --rm --network none --cpus 1 --memory 768m \
  --mount "type=bind,source=$PWD/build/linuxStorageTest/runtime,target=/runtime,readonly" \
  eclipse-temurin:26-jdk@sha256:c0fe66ea21e972724000cf402f8081c7841d960839f69cb0754f40b40f74b2cc \
  java -Xms256m -Xmx512m -cp '/runtime/classes:/runtime/jars/*' \
  io.github.core607.poketto.content.SearchParsingProbe
```

Use the same probe source when comparing another matcher revision. Results vary
with the corpus, JVM and host; no latency threshold gates CI.

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

A valid-Markdown regression first fails against the baseline because each of
three metadata exclusions still invokes reading-text extraction. After the
reorder it verifies zero parser calls for excluded tags, dates before the lower
bound and dates after the upper bound, while inclusive date boundaries still
match. Shared reading-text, repository-reader and public site search tests pass.
The source probe records equal match/snippet-length checksums before and after;
it does not assert wall-clock timing in CI.
