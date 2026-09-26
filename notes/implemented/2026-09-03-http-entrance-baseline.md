# HTTP Entrance Baseline

Date: 2026-09-03
Status: Implemented

## Problem

Every accepted proposal assumes a Spring HTTP surface: the [Next.js frontend](2026-08-30-nextjs-frontend.md) routes `/api/**` to Spring and expects stable problem responses, [continuous delivery](2026-09-03-continuous-delivery.md) succeeds only after a real health entrance passes, managed assets need a delivery URL, and [invitation-only membership](2026-08-27-invitation-only-membership.md) needs sessions and CSRF on real endpoints. The `web` module owned none of that: no health endpoint, no error contract, and no route over the implemented content module.

## Decision

### Health

Spring Boot Actuator serves `/actuator/health`, and it is the only management endpoint on the web surface. Details are never shown, and the liveness and readiness probes are enabled at `/actuator/health/liveness` and `/actuator/health/readiness`. Database health participates automatically when a data source exists. The public reverse proxy never forwards `/actuator`; deployment checks reach it on the application port.

Health reports that the process and the database respond, and whether served content exists and is within its stale bound through the `contentSnapshot` indicator owned by the public content snapshot of the [repository authoring foundations](2026-09-05-repository-authoring-foundations.md). No indicator contacts the remote repository, so a provider outage does not fail the probe until the served content outlives its stale bound.

### Problem responses

Every error leaves the HTTP boundary as an RFC 9457 `application/problem+json` document. Spring MVC's own failures (unknown route, unsupported method, unreadable body) render as problems through `spring.mvc.problemdetails.enabled`. `ProblemResponses` maps domain failures:

| Failure | Status | Title | Detail |
|---|---|---|---|
| `PublicResourceNotFoundException`, `DocumentNotFoundException` | 404 | Not found | the exception message |
| `DocumentConflictException` | 409 | Conflict | the exception message, plus a `liveRevision` property when the conflict carries one |
| `RepositoryConflictException` | 409 | Conflict | fixed text |
| `RepositoryWriteAmbiguousException` | 503 | Write outcome unknown | fixed text telling the caller to re-read before retrying |
| `ContentRepositoryException` | 503 | Repository unavailable | fixed text; on an administration route called by a signed-in account, fixed recovery text and a `REPOSITORY_RETRY` or `REPOSITORY_RECONNECT` code |
| any other exception | 500 | Internal server error | fixed text |

Repository failures keep their diagnostic in the server log at `WARN` and never in the response, because their messages name workspaces and repository state. Public routes never receive the recovery code, even from a visitor with a browser session. Unexpected exceptions are logged at `ERROR` with their stack trace and leave the boundary through a fixed, sanitized 500 problem. Spring MVC failures retain their specific status instead of falling through to that generic response.

### Public document API

The default workspace's public content is served by `GET /api/public/documents` and `GET /api/public/document?route=...`, which the [repository authoring foundations](2026-09-05-repository-authoring-foundations.md) define; per-space public routes live below `/api/public/spaces/{slug}` ([website delivery](2026-09-14-workspace-public-delivery.md)). Rendering to HTML, sanitization, and CSP belong to the frontend.

`PublicDocuments` fixes the public scope before route lookup and takes no visibility parameter, so no entrance built on it can widen the scope. Missing, private, malformed and outdated references reveal no resource details. Every request reads the public content snapshot, which a write updates immediately and a background refresh re-validates; no request contacts the remote.

The route beans share the `poketto.workspace.catalog.enabled` condition with the content initializer, so a context without a database also has no content route. The `/api/` prefix follows the same-origin split of the Next.js frontend, so later routes and the proxy rule do not move.

## Alternatives

**A hand-written health controller.** Fewer dependencies, but it would reimplement database health and the probe distinction. Actuator with a one-endpoint exposure list keeps the same surface.

**Return rendered HTML.** That would select a template engine while the accepted frontend proposal moves rendering into Next.js. The JSON contract serves either frontend.

**Plain status codes without a body.** Agents and browser code need a stable machine-readable error identity, and the write contracts already carry a live revision that a conflict response must be able to return.

**Cache scan results across requests.** It would hide a publish behind an uncoordinated cache. The frontend proposal requires an explicit freshness contract before any cross-request cache.

## Consequences

A public request costs a lookup in the served snapshot; the remote is off the request path under the snapshot's bounded freshness contract.

Problem titles and details are visible behavior. Changing one changes what clients and agents branch on.

Public routes are public-content-only by construction; private routes rely on the membership and workspace authorization that the identity notes own.

## Verification

`PublicDocumentControllerTests` pins the public scope and the problem mapping, `PokettoApplicationTests` the health-only management surface, and `PostgresIntegrationIT` health with a real database and remote authority.
