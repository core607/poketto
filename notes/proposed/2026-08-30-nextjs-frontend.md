# Next.js Frontend Boundary

Date: 2026-08-30
Status: Proposed

[Phase-one delivery](2026-09-05-phase-one-daily-use.md) includes the public blog and Markdown administration interface with real-browser evidence. Visitor-Q&A controls are excluded from that delivery.

## Problem

The [requirements](../implemented/2026-08-25-requirements-and-architecture.md) select JTE, htmx, and Tailwind for a server-rendered public blog and its administration pages. The [development baseline](../implemented/2026-08-26-development-baseline.md) consequently defines one Spring Boot artifact with a `web` application module, although no user-facing route or JTE template has been implemented.

Poketto needs two rendering modes. Public articles, tag and archive pages, RSS, and the sitemap need complete server-rendered output. Administration pages need longer-lived client state for content editing, membership, keys, repository diagnostics, and visitor-Q&A controls. Implementing both through server templates and fragment response contracts would split one interface across Java templates, browser scripts, and endpoint-specific HTML protocols.

The frontend needs one typed component model for public rendering and application-like interaction without moving content authority, authorization, or business operations out of Spring. Adding a JavaScript renderer must also have measured runtime costs rather than silently redefining deployment sizing.

## Proposal

### Frontend and backend ownership

- Replace JTE and htmx with Next.js App Router, React, TypeScript, and Tailwind. Keep the frontend in a top-level `frontend/` workspace with a pinned active-LTS Node.js toolchain and a committed package-manager lockfile.
- Run Next.js and Spring Boot as separate processes and OCI images. Next.js owns browser routes, HTML rendering, frontend assets, view state, and presentation-only resources such as RSS and sitemap responses.
- Spring remains the only business backend. It owns workspace resolution, authentication, authorization, CSRF enforcement, content and Git operations, repository-backed retrieval, sandboxed agent execution, Q&A, MCP, budgets, audit records, and persistence. The read and execution boundary follows [Repository-native retrieval and sandboxed agent execution](2026-09-01-repository-native-retrieval-and-sandboxed-execution.md).
- Next.js never reads a workspace repository cache, ManagedBlobStore, repository-image cache, or database directly. It obtains data through documented Spring HTTP contracts. The first implementation uses neither Server Actions nor Route Handlers for domain reads or mutations; browser mutations call Spring APIs directly.
- The Spring `web` module owns HTTP API contracts and their mapping to application modules. It does not own HTML templates. The implementation updates the development baseline and both requirements documents when this boundary ships.

### Routing and trust boundary

A same-origin reverse proxy sends `/api/**` and `/mcp/**` to Spring and browser page routes to Next.js. Health entrances remain separately addressable for deployment checks. The public origin does not expose arbitrary Spring management endpoints.

Spring Security owns human sessions and session cookies. Next.js may forward the incoming cookie to Spring while rendering an authenticated page, but it cannot mint a session, infer a capability, or treat decoded client state as authorization. State-changing browser requests go to Spring with its CSRF contract. Public page rendering calls an API whose interface can return only public content; it does not accept a caller-selected visibility scope.

Errors cross the HTTP boundary as stable problem responses. Next.js maps them to pages and controls without parsing log messages or duplicating authorization decisions. Missing and unauthorized workspace-owned resources retain the indistinguishable behavior required by the workspace boundary.

### Rendering and freshness

- Public article, tag, archive, and landing routes return meaningful HTML in the initial response. Client JavaScript may enhance navigation and interaction but is not required to read an article.
- Administration routes use React client components where interaction needs browser state. Server rendering is not required for private dashboards solely for consistency with public pages.
- Mutable public content is rendered from the committed state exposed by Spring. The first implementation does not keep a cross-request Next.js data or HTML cache for mutable content, so a successful content commit cannot remain hidden behind an uncoordinated frontend cache. Immutable build assets retain normal long-lived caching.
- Revision-aware caching, on-demand revalidation, and CDN caching may be added only with an explicit freshness contract. That contract must scope cache keys by workspace and public route, define invalidation after commit, expose invalidation failure, and coordinate multiple Next.js replicas. A write acknowledgement does not imply cache invalidation unless that later contract says so.

### Resource and build boundary

Frontend dependencies and production assets are built in CI. A production host runs the prebuilt Next.js output and never runs `next build`, TypeScript compilation, or package installation during deployment. Runtime image optimization is disabled unless measured evidence shows that its memory peaks fit the same budget; managed images and materialized repository images use the immutable delivery path owned by [managed assets and repository image materialization](2026-09-01-repository-asset-blob-store.md) instead.

Deployment sizing is not set by this frontend decision. The implementation measures steady-state and peak resource use for the complete prebuilt runtime against the project's deployment criteria and records the evidence in the appropriate operational record.

The Gradle `check` task remains the repository-wide verification entrance. It invokes lockfile-based frontend formatting or linting, type checking, tests, and production build in addition to the existing Java and repository checks. The implementation updates the [continuous-delivery proposal](../implemented/2026-09-03-continuous-delivery.md) to account for the frontend artifact without duplicating environment-specific deployment details here.

## Implementation scope and dependencies

This proposal reverses only the frontend selection in the requirements. It retains Spring MVC for JSON, MCP, health, and other non-page HTTP entrances; Tailwind remains the styling foundation. The content, workspace, retrieval, execution, auth, and MCP boundaries do not move into Next.js.

The implementation includes the `frontend/` workspace, reproducible Node and package-manager versions, Next.js production packaging, the same-origin routing contract, frontend verification in `check`, removal of JTE and htmx from the selected stack, focused contract tests, and the runtime memory exercise. It updates the English and Chinese requirements counterparts, the development baseline, command documentation, and the continuous-delivery proposal to describe the built artifact topology.

The migration should accompany the first real browser-facing vertical slice rather than add a placeholder product UI. That slice must prove one public server-rendered content route and one authenticated administration interaction through Spring. No compatibility route or parallel JTE implementation is required because the project has no external users and no user-facing JTE code exists.

## Alternatives considered

**Keep JTE and htmx.** This preserves one runtime process and the smallest memory surface. It also makes interactive administration state span Java templates, fragment endpoints, and browser conventions. The additional Next.js process is accepted only when the production-like resource exercise passes.

**Use Vue with Nuxt.** Nuxt provides server rendering, static generation, and client-only administration routes with a portable Node runtime. Its primary single-file component model remains template-centered. React and TSX provide one TypeScript expression model for composition, and Next.js supplies the required public rendering modes without introducing a second backend.

**Use React with Vite as a client-only SPA.** It would add almost no production-server memory and would simplify deployment. Public articles would not have complete initial HTML without a second prerendering system, so the result would violate the public rendering requirement.

**Use a static Next.js export.** Static output needs no Node.js runtime, but workspace content changes independently of the application build. Every publish would require a site rebuild or would leave public pages stale. Request-time rendering keeps content publication independent of application deployment.

**Use Next.js as a full-stack backend.** Server Actions and Route Handlers could implement sessions, writes, and queries, but that would duplicate the Spring module, security, transaction, and audit boundaries. Next.js remains a presentation process over Spring contracts.

**Increase deployment resources before implementation.** This would create comfortable headroom without evidence that the selected frontend needs it. Build work stays outside the production runtime, runtime processes receive explicit limits, and the implementation reports measured usage before changing deployment criteria.

## Acceptance

- The requirements name Next.js App Router, React, TypeScript, and Tailwind and no longer select JTE or htmx. The development baseline identifies the frontend artifact and the Spring `web` module's API ownership.
- A public content route returns the article title and body in the initial HTML with JavaScript disabled. Tag or archive navigation, RSS, and sitemap generation consume only public Spring contracts.
- An authenticated administration interaction reads and mutates through Spring, preserves Spring Security session and CSRF behavior, and cannot bypass a workspace capability by calling Next.js directly.
- Frontend code has no database or repository access. Server Actions and domain Route Handlers are absent from the first implementation, and Spring remains the only mutation entrance.
- A successful content commit is visible on a subsequent uncached public request. Tests prove that one workspace's route, response, error, and browser cache state cannot reveal another workspace.
- `./gradlew check` installs dependencies from the lockfile and runs frontend linting, type checking, tests, and a production build alongside the existing suites. Dependency or generated-output drift fails the gate.
- A production-like runtime exercise starts the prebuilt application components under the project's deployment limits; warms the implemented public and administration paths; performs representative reads and a content mutation; and records steady-state and peak resource use. No process relies on swap or incurs an OOM kill. Failure stops implementation for an explicit scope or deployment-sizing decision instead of silently changing the requirement.
- The frontend OCI image contains production output and runtime dependencies only. A production deployment performs no package installation or frontend build.
- A real browser run supplies the screenshot or recording evidence required by `ui-evidence`, and `git diff --check` passes.

## Risks

A second runtime increases resident memory, image count, health checks, and deployment coordination. The resource acceptance test protects the minimum host but cannot predict every future feature; later work must keep its own memory and concurrency bounded.

Next.js caching and rendering defaults evolve. Pinning the framework and making mutable-content freshness explicit prevent an upgrade from silently changing public visibility, but upgrades require contract and browser evidence rather than dependency-only validation.

Server-side rendering adds an HTTP hop from Next.js to Spring. Public reads are inexpensive and local in the default topology, but a slow or unavailable backend now affects rendered pages through two processes. Timeouts and error mapping must fail clearly without serving private or cross-workspace fallback data.

React makes rich client interaction easy to add. That does not change the v1 exclusion of a rich-text editor, nor does it justify moving business state into browser stores or Next.js server code.
