# Search reading return browser acceptance

- Source: `2451ec16fe4a021220866e9671676349cc6148f4`
- Fixture source: `src/integrationTest/java/io/github/core607/poketto/acceptance/AcceptanceApplication.java`
- Fixture: fresh `poketto-acceptance` Compose project with PostgreSQL 17.11, the staged Spring acceptance runtime, the production Next.js image, and Caddy local HTTP routing.
- Origin: `http://127.0.0.1:38180`
- Browser: Chrome extension acceptance tab created for this run. Existing user tabs and processes were left untouched.
- Credentials and remote repository coordinates are intentionally omitted.

## Observed flows

1. Site search `回链验收` returned 28 synthetic public documents. The first page rendered literal `mark` highlights and safe article links.
2. Pagination reached `offset=12` and showed results 13 through 24. Result 24 was selected after scrolling to the lower part of the page. The site result followed the legacy `/read` redirect to `/s/home/read/...` and produced a structured Return link carrying query, offset, and a generated entry id.
3. Browser Back restored result 24, its heading link focus, and the lower scroll position (`scrollY` 1011 after layout settled). Explicit Return restored the same result and position.
4. A return entry from offset 12 did not restore on offset 0. A same-scope search returned 12 cards with `searchScope=space`; opening and explicitly returning from `/s/home/search` restored result 24 with the space search URL.
5. After the captured result was removed through the real authenticated repository patch API in the synthetic fixture, the fresh offset-12 page showed 27 results (13 through 23 and 25), no result-24 anchor, no focus target, and a normal top-level search page instead of an error.

## Evidence capture

The CUA browser tool returned the screenshots as original JPEG bytes. A one-time receiver bound only to `127.0.0.1` wrote those bytes to this ignored directory without decoding, editing, or re-encoding them. The recorded hashes and dimensions are:

- `desktop-site.jpg`: 53,676 bytes, SHA-256 `462cdb98cf708ecb2dcb7f4f04c87a3d9c4d93c8bdbc561c350494b1c47da20e`, 1905x938.
- `desktop-offset12.jpg`: 53,568 bytes, SHA-256 `c23705331719430564efdee281fd3ae03806e0ad4bf3dfce7682531f6a8a40df`, 1905x938.
- `desktop-article.jpg`: 34,443 bytes, SHA-256 `23f75523a559578b5d0f9cc63f0532131b3c20596960d6a283314d51b8ec07c2`, 1920x945.
- `desktop-back.jpg`: 58,076 bytes, SHA-256 `431001f77605932c5fa9c2d2737addfa9980ad2e32ec55d3bf877a0d67278564`, 1905x938.
- `desktop-explicit-return.jpg`: 59,013 bytes, SHA-256 `81eccb0140ee541bd0718a04a000a5c2a28679e77bc1e86d77086a5304504528`, 1905x938.
- `desktop-removed.jpg`: 53,568 bytes, SHA-256 `c23705331719430564efdee281fd3ae03806e0ad4bf3dfce7682531f6a8a40df`, 1905x938.
- `desktop-removed-bottom.jpg`: 58,191 bytes, SHA-256 `ac5a43c507cb58844ca759642fc095c72d516ac89f687ea11df05ebaa45bb666`, 1905x938.
- `mobile-search.jpg`: 23,130 bytes, SHA-256 `5e6f5f1159b3aba16cbf0d040c18a3a2747a38cbcbe96448b940ba86202dd8f0`, 375x812.
- `mobile-article.jpg`: 19,021 bytes, SHA-256 `95f2b4fb1ba79626417f71f07904c45705b5728360971e907f829ea45fe05bde`, 390x844.
- `mobile-returned.jpg`: 23,316 bytes, SHA-256 `705fd14b6228cdca3139bd208ad58267822bfdc27cfa0cbb54aa38b333107dc1`, 375x812.

The second fresh-stack run used the same source revision and a browser-level 390x844 viewport. The search page had no horizontal overflow (`document.documentElement.scrollWidth` 375), the article had no horizontal overflow at 390, and result 24 followed the legacy redirect to a structured article URL. Its mobile Return link restored the offset-12 search URL, result 24 focus, and `scrollY=2564`.

## Limits

The storage-disabled path is covered by the focused frontend test (`9/9` on this source), but the browser extension exposes no page-mutation or storage-blocking control; no browser storage-disabled claim is made here. Production HTTPS, external content, and real MCP clients remain outside this local synthetic acceptance.
