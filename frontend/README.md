# Frontend

Next.js presents Spring-owned content and administration APIs. It does not access Git, blob storage, or PostgreSQL. All public pages render on each request without a cross-request data cache.

Use Node.js 24.19.0 and npm 12.0.2. Install with `npm ci`, then run `npm run check` for formatting, TypeScript, contract tests, and the production build. `npm run dev` starts the local frontend; `npm start` serves a completed production build. The build also emits Next.js standalone output for container packaging.

`POKETTO_API_BASE_URL` supplies the server-side Spring origin; its development default is `http://127.0.0.1:8080`. Browser requests always use same-origin `/api` URLs. Development rewrites proxy these requests to Spring. Production requires the deployment reverse proxy to route `/api/**` and `/mcp/**` to Spring and other routes to Next.js. `POKETTO_PUBLIC_URL` is the canonical public origin used in RSS and sitemap links; those resources return 503 while it is absent. Keep operator origins and credentials in private runtime configuration.

For local HTTP development, Spring must explicitly allow the browser origin and disable Secure session cookies through its development configuration. Production uses Secure cookies and HTTPS. Credentials remain in React state for the current interaction; neither cookies nor credentials are stored in browser local storage.

## Routes and rendering

`/privacy` and `/terms` are public service-information pages linked from the footer and login form. `POKETTO_SUPPORT_EMAIL` supplies their public contact address at frontend runtime. It is not a sending credential. Operators must set their own contact and review these pages against their installation's actual data handling before publishing them.

Each published space is served under `/s/<slug>`. Its home page renders the space's root `index.md` and folder gallery above the article list; `/s/<slug>/tags`, `/archive` and `/search` list its public content, and articles use `/s/<slug>/read/<encoded repository route>`, with `/s/<slug>/read` for the logical root route `/`. This namespace permits repository routes such as `/admin` without shadowing application pages. `/read/...`, `/tags` and `/archive` permanently redirect to the default space's equivalents. Application pages are `/` (cross-space discovery and the following feed), `/search` (cross-space search), `/community` (personal lists) and `/admin` (sign-in and the studio); `/rss.xml` and `/sitemap.xml` are presentation resources over public Spring APIs. The global bar links discovery, search and the account menu only; each space site carries its own navigation.

Styles live in `app/styles/` as design tokens, shared controls and the public places. Screens not yet rebuilt under the [frontend remake](../notes/proposed/2026-09-23-frontend-remake.md) render inside a `.legacy` wrapper whose scoped rules come from `app/legacy.css`.

Public and editor preview share one restricted Markdown component. Raw HTML is discarded. Links use allowed schemes or Spring-resolved routes. Images render only from Spring-provided same-origin asset mappings; authored external or unresolved image URLs never become browser requests. Private image entrances are accepted only in authenticated preview components. Spring parses the editor's full source and returns the preview body, link mappings, image mappings, and gallery.

The editor retains the raw loaded text, revision, and base commit for atomic patches. Conflicts and uncertain outcomes retain the draft for manual reconciliation; requests are not blindly retried. An upload uses a per-file idempotency key that survives retries in the mounted picker. Upload acknowledgement and article save acknowledgement have separate messages. Managed credentials are shown once after creation and discarded when dismissed.

## Evidence boundaries

Contract tests cover static HTML rendering, URL and image restrictions, CSRF forwarding, upload idempotency headers, uncertain writes, and publication-snapshot consistency. They use explicit test fixtures and do not constitute browser acceptance. Real-browser screenshots, editing flows, both MCP clients, and deployment resource measurements must run against the integrated application before release acceptance.
