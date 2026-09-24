# Blog and Browser Administration

Date: 2026-09-06
Status: Implemented

## Decision

The [Next.js frontend record](2026-08-30-nextjs-frontend.md) separates presentation from Spring-owned content and authorization. The [frontend workspace](../../frontend/README.md) implements that boundary with server-rendered public articles, tags, archive, search, RSS and sitemap, plus a Chinese administration interface for raw Markdown, previews, images, members and keys.

Next.js reads public Spring APIs without forwarding a browser identity or caching mutable content across requests. Browser mutations use same-origin Spring APIs with the current session and CSRF token. Git, database access, authorization, publication policy and image grants remain Spring responsibilities. The renderer discards raw HTML and accepts images only through Spring-resolved references; the public view and authenticated preview use the same restricted Markdown rules.

Public page offsets accept nonnegative decimal integers within the owning API's bounds: 10000 for articles and 320000 for the tag catalogue. Missing, malformed, repeated or out-of-range values select the first page before an API request. Valid offsets retain their value; this presentation rule does not relax backend limits.

Search text longer than 200 UTF-16 code units stays in the input with a Chinese validation message and is not sent to the API. A tag filter longer than 64 Unicode code points shows an inline Chinese notice without an API read. These lengths match `DocumentSearch`, which counts the query in UTF-16 code units and the tag in code points. Repeated search/tag values retain the existing comma-joined API serialization before validation; neither input is silently truncated.

The editor keeps the loaded revision and commit alongside the draft. A rename or delete becomes an atomic repository patch. Conflict and uncertain-response states retain the draft instead of retrying a write. Image uploads use independent idempotency keys and report their acknowledgement separately from a document save.

[Authoring and discovery](2026-09-23-authoring-and-discovery-experience.md) adds
bounded local draft recovery, image paste/drop and explicit publish/withdraw actions
over the existing save, upload and atomic move services.

Member suspension, key revocation, file deletion and discarding unsaved edits use a shared in-page modal dialog. It identifies the action and target, initially focuses Cancel, and treats Escape or unmounting the caller as cancellation. Closing the dialog restores focus to the invoking control when it remains available. The application waits for explicit confirmation before sending the mutation or discarding the draft. Closing or reloading a browser tab with unsaved edits retains the browser's unload warning.

Repository image choices encode each filename segment while retaining relative parent traversal. New image references and previews use the draft's pending destination path. Changing that path clears old image choices without rewriting the draft or its existing references.

## Build and local acceptance

Gradle `frontendCheck`, required by `check`, runs formatting, type checking, behavioral tests and the production build with the toolchain pinned in the [frontend boundary](2026-08-30-nextjs-frontend.md). The root Docker ignore rules exclude local dependencies, build caches and operator environment files from images.

The [browser acceptance entrance](../../acceptance/README.md) runs actual Spring, PostgreSQL, Next.js and same-origin Caddy against fresh synthetic Git data. `stageAcceptanceRuntime` compiles its seed application from integration-test sources; the production image never contains that fixture. The seed refuses a nonempty root, so its documented fresh-run lifecycle is distinct from production restart. Local HTTP acceptance disables Secure cookies explicitly through the fixture's property source and never changes the production default.

## Alternatives

The [frontend boundary](2026-08-30-nextjs-frontend.md) records why JTE with htmx and a static export lost to request-time Next.js rendering. The [stack delivery record](2026-09-05-blog-stack-delivery.md) owns integrated deployment.

## Verification

`frontend/tests/rendering.test.tsx`, `pagination-pages.test.tsx`, `api.test.tsx`, `repository-write.test.tsx`, `editor-paths.test.tsx` and `confirmation-actions.test.tsx` pin the renderer restrictions, page-parameter bounds, CSRF forwarding, upload idempotency, uncertain writes, image destinations and confirmation dialogs against explicit API fixtures.
