# Blog and Browser Administration

Date: 2026-09-06
Status: Implemented

## Decision

The [Next.js frontend proposal](../proposed/2026-08-30-nextjs-frontend.md) separates presentation from Spring-owned content and authorization. The [frontend workspace](../../frontend/README.md) implements that boundary with server-rendered public articles, tags, archive, search, RSS and sitemap, plus a Chinese administration interface for raw Markdown, previews, images, members and keys.

Next.js reads public Spring APIs without forwarding a browser identity or caching mutable content across requests. Browser mutations use same-origin Spring APIs with the current session and CSRF token. Git, database access, authorization, publication policy and image grants remain Spring responsibilities. The renderer discards raw HTML and accepts images only through Spring-resolved references; the public view and authenticated preview use the same restricted Markdown rules.

Public page offsets accept nonnegative decimal integers within the owning API's bounds: 10000 for articles and 320000 for the tag catalogue. Missing, malformed, repeated or out-of-range values select the first page before an API request. Valid offsets retain their value; this presentation rule does not relax backend limits.

The editor keeps the loaded revision and commit alongside the draft. A rename or delete becomes an atomic repository patch. Conflict and uncertain-response states retain the draft instead of retrying a write. Image uploads use independent idempotency keys and report their acknowledgement separately from a document save.

Repository image choices encode each filename segment while retaining relative parent traversal. New image references and previews use the draft's pending destination path. Changing that path clears old image choices without rewriting the draft or its existing references.

## Build and local acceptance

Node.js 24.19.0, npm 12.0.2 and the committed lockfile define the frontend build. Gradle `frontendCheck`, also required by `check`, runs formatting, type checking, behavioral tests and the production build. The frontend Dockerfile packages standalone production output; the root Docker ignore rules exclude local dependencies, build caches and operator environment files.

The [browser acceptance entrance](../../acceptance/README.md) runs actual Spring, PostgreSQL, Next.js and same-origin Caddy against fresh synthetic Git data. `stageAcceptanceRuntime` compiles its seed application from integration-test sources; the production image never contains that fixture. The seed refuses a nonempty root, so its documented fresh-run lifecycle is distinct from production restart. Local HTTP acceptance disables Secure cookies explicitly through the fixture's property source and never changes the production default.

## Alternatives and remaining work

JTE and htmx would keep one process, but editing state would span server templates, fragment responses and browser scripts. Next.js supplies one component model while preserving Spring as the business boundary. A static export would require a separate content publication and invalidation contract; request-time rendering uses Spring's current public snapshot instead.

The broader frontend proposal remains proposed: final deployment integration, production resource measurements and release acceptance have separate requirements. This browser entrance supplies no MCP transport or execution worker. Synthetic HTTP evidence does not satisfy the final HTTPS-domain and real-content acceptance criteria in [phase one](../proposed/2026-09-05-phase-one-daily-use.md).

## Verification

The frontend gate covers 26 behavioral tests, including initial article HTML, URL/image restrictions, Chinese routes and fragment targets, CSRF forwarding, upload idempotency, unchanged-save acknowledgement and uncertain-write handling. Public page consumers exercise malformed, repeated, overflowing and endpoint-boundary offsets through an HTTP fixture, retaining Chinese page content and search/tag filters. A mounted editor test changes the pending destination, chooses an image and checks the emitted preview requests and unchanged draft bytes. These tests use explicit API fixtures. Real-browser acceptance against the integrated content backend and screenshots of its exact source version remain required before this interface is ready for review.
