# Frontend

Next.js renders Spring's content and administration APIs without accessing Git, blobs or PostgreSQL. Public pages render per request without a cross-request cache.

With Node.js 24.19.0 and npm 12.0.2, run `npm ci` then `npm run check` for formatting, TypeScript, contract tests and the production build. `npm run dev` starts the local frontend; `npm start` serves a completed production build. Builds emit standalone Next.js container output.

`POKETTO_API_BASE_URL` supplies the server-side Spring origin; its development default is `http://127.0.0.1:8080`. Browser requests always use same-origin `/api` URLs. Development rewrites proxy these requests to Spring. Production requires the deployment reverse proxy to route `/api/**`, `/mcp/**` and the three `/.well-known/oauth-*` discovery paths to Spring and other routes to Next.js, as the [production Caddyfile](../deploy/Caddyfile) does. `POKETTO_PUBLIC_URL` is the canonical public origin used in RSS and sitemap links; those resources return 503 while it is absent. Keep operator origins and credentials in private runtime configuration.

For local HTTP development, Spring must explicitly allow the browser origin and disable Secure session cookies through its development configuration. Production uses Secure cookies and HTTPS. Credentials stay in React state during interactions; cookies and credentials never enter local storage.

## Routes and rendering

`/privacy` and `/terms` are public service-information pages linked from the footer and login form. `POKETTO_SUPPORT_EMAIL` supplies their public contact address at frontend runtime. It is not a sending credential. Operators must set their own contact and review these pages against their installation's actual data handling before publishing them.

Each published space is served under `/s/<slug>`. Its home page renders the space's root `index.md` and folder gallery above the article list; `/s/<slug>/tags`, `/archive` and `/search` list its public content, and articles use `/s/<slug>/read/<encoded repository route>`, with `/s/<slug>/read` for the logical root route `/`. Each space also serves `/s/<slug>/rss.xml`, `/s/<slug>/history/<route>` and `/s/<slug>/cover/<route>`, and `/community?tab=<list>` opens one personal list directly. This namespace permits repository routes such as `/admin` without shadowing application pages. `/read/...`, `/tags` and `/archive` temporarily redirect to the default space's article and lists; the redirects stay temporary because the default space can change. Application pages are `/` (cross-space discovery and the following feed), `/search` (cross-space search), `/community` (personal lists), `/account` (the account's group and login security) and `/admin` (sign-in and the studio), plus `/site` (administrator-only account, space, application and report management); `/rss.xml` and `/sitemap.xml` are presentation resources over public Spring APIs. Global-bar sign-in returns to the current page through a validated `/admin?next=`. The global bar links discovery, search and the account menu only, offering the studio only to accounts that can create or already have a space. Site sections, filters, pagination and selected review targets are URL state, restored on reload and browser navigation. Each space site carries its own navigation.

Styles live in `app/styles/`: design tokens and element resets, shared controls, the public places, the studio shell and panels, and the editor. Components style themselves with classes; studio panels get element defaults through `:where()` so component rules always win.

Public and editor preview share one restricted Markdown component. Raw HTML is discarded. Links use allowed schemes or Spring-resolved routes. Images render only from Spring-provided same-origin asset mappings; authored external or unresolved image URLs never become browser requests. Private image entrances are accepted only in authenticated preview components. Spring parses the editor's full source and returns the preview body, link mappings, image mappings, and gallery.

The editor retains the raw loaded text, revision, and base commit for atomic patches. Conflicts and uncertain outcomes retain the draft for manual reconciliation; requests are not blindly retried. An upload uses a per-file idempotency key that survives retries in the mounted picker. Upload acknowledgement and article save acknowledgement have separate messages. Managed credentials are shown once after creation and discarded when dismissed.

## Evidence boundaries

Contract tests cover static HTML rendering, URL and image restrictions, CSRF forwarding, upload idempotency headers, uncertain writes, and publication-snapshot consistency. They use explicit test fixtures and do not constitute browser acceptance. Real-browser screenshots, editing flows, both MCP clients, and deployment resource measurements must run against the integrated application before release acceptance.
