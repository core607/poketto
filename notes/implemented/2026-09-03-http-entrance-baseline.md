# HTTP Entrance Baseline

Date: 2026-09-03
Status: Implemented

## Problem

Every accepted proposal assumes a Spring HTTP surface: the [Next.js frontend](../proposed/2026-08-30-nextjs-frontend.md) routes `/api/**` to Spring and expects stable problem responses, [continuous delivery](../proposed/2026-08-26-continuous-delivery.md) succeeds only after a real health entrance passes, [managed assets](../proposed/2026-09-01-repository-asset-blob-store.md) need a delivery URL, and [invitation-only membership](../proposed/2026-08-27-invitation-only-membership.md) needs sessions and CSRF on real endpoints. The `web` module owned none of that: no health endpoint, no error contract, and no route over the implemented content module.

## Decision

### Health

Spring Boot Actuator serves `/actuator/health`, and it is the only management endpoint on the web surface. Details are never shown, and the liveness and readiness probes are enabled at `/actuator/health/liveness` and `/actuator/health/readiness`. Database health participates automatically when a data source exists. The public reverse proxy never forwards `/actuator`; deployment checks reach it on the application port.

Health reports that the process and the database respond. It does not contact the remote repository, so a provider outage surfaces as a problem response on content routes rather than as a failing probe that restarts a healthy process.

### Problem responses

Every error leaves the HTTP boundary as an RFC 9457 `application/problem+json` document. Spring MVC's own failures (unknown route, unsupported method, unreadable body) render as problems through `spring.mvc.problemdetails.enabled`. `ProblemResponses` maps domain failures:

| Failure | Status | Title | Detail |
|---|---|---|---|
| `PublicResourceNotFoundException`, `DocumentNotFoundException` | 404 | Not found | the exception message |
| `DocumentConflictException` | 409 | Conflict | the exception message, plus a `liveRevision` property when the conflict carries one |
| `RepositoryConflictException` | 409 | Conflict | fixed text |
| `RepositoryWriteAmbiguousException` | 503 | Write outcome unknown | fixed text telling the caller to re-read before retrying |
| `ContentRepositoryException` | 503 | Repository unavailable | fixed text |

Repository failures keep their diagnostic in the server log at `WARN` and never in the response, because their messages name workspaces and repository state.

### Public document API

`GET /api/public/documents` lists the default workspace's public documents, newest publication first, as id, title, tags, `publishedAt`, and `updatedAt`. `GET /api/public/documents/{id}` adds `createdAt` and the uninterpreted Markdown body. `publishedAt` is null only for a document an owner made public through a direct push without recording a publication time. Rendering to HTML, sanitization, and CSP belong to the presentation layer and are not implemented.

`PublicDocuments` is constructed over the content store without a visibility parameter, so no entrance built on it can widen the scope. A malformed, unknown, or private id produces byte-identical not-found problems apart from the echoed id. Every request resolves current remote `main`; there is no cross-request cache, so a publish is visible on the next request.

The default workspace is the only routable workspace, resolved through the workspace catalog. The route beans share the `poketto.workspace.catalog.enabled` condition with the content initializer, so a context without a database also has no content route.

### Not included

Authentication, sessions, CSRF, write routes, tag and archive listings, RSS, sitemap, MCP, and workspace routing by slug or domain. The `/api/` prefix follows the same-origin split in the Next.js proposal so later routes and the proxy rule do not move.

## Alternatives

**A hand-written health controller.** Fewer dependencies, but it would reimplement database health and the probe distinction. Actuator with a one-endpoint exposure list keeps the same surface.

**Return rendered HTML.** That would select a template engine while the accepted frontend proposal moves rendering into Next.js. The JSON contract serves either frontend.

**Plain status codes without a body.** Agents and browser code need a stable machine-readable error identity, and the write contracts already carry a live revision that a conflict response must be able to return.

**Cache scan results across requests.** It would hide a publish behind an uncoordinated cache. The frontend proposal requires an explicit freshness contract before any cross-request cache.

## Consequences

A public request costs one remote fetch and cache reset. That is acceptable for a personal blog on one server; measured evidence decides whether a commit-keyed cache is worth its freshness contract.

Problem titles and details are visible behavior. Changing one changes what clients and agents branch on.

With no authentication, the only routes are public-content-only by construction. Adding a private route requires the membership implementation first.

## Verification

- `PublicDocumentControllerTests` covers public-only listing and ordering, body retrieval, indistinguishable not-found responses, sanitized repository and ambiguous-write problems, and problem rendering for unknown routes.
- `PokettoApplicationTests` covers health without details, both probes, and the absence of other management endpoints in a context without a database.
- `PostgresIntegrationIT` covers health with the database and a create, publish, and read sequence through the real remote authority, including a private document staying hidden.
- `./gradlew test`, `./gradlew integrationTest`, `./gradlew repoCheck`, and `git diff --check` pass.
