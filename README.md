# Workspace website publication: browser evidence

Demonstrated application and frontend revision: `11a902ddab0b7aab02b7304742ed02d0f9cdbee3`.
Captured on 2026-09-14 (Asia/Hong_Kong). Later product-branch edits are documentation and test-only; the application and frontend sources are unchanged.

The run used `./gradlew stageAcceptanceRuntime` followed by `docker compose --env-file <ignored disposable environment> -p poketto-public-space -f acceptance/compose.yaml up -d --build`. The real Spring application, PostgreSQL 17.11, production Next frontend and Caddy ran together. The acceptance application created two independent synthetic Git repositories and a disposable owner account. No production repository, user data or provider credentials were used. The optional multiple-workspace fixture was enabled; this run does not exercise repository-provider provisioning.

Browser: Codex in-app browser. Desktop captures use the default 1280 by 720 viewport; mobile search uses a temporary 390 by 844 override, reset afterward. Captures are separate unchanged screenshots from one final run, not a recording or composite. Native scrollbars affect captured content width.

## Observed flow

1. Log in and select the second space. Its website is initially disabled. Cancel the enable confirmation and confirm that the state stays disabled.
2. Enable explicitly. The response updates the state and displays the space website link: [enabled publication](publication-enabled.jpg).
3. Open the space website, follow its authored article link, and verify the image reports loaded with a positive natural width: [space article](space-article.jpg).
4. Pause the disposable backend, reload the article, and observe the temporary-unavailability page. Resume the backend and click the page's reload action. The server-rendered article returns without editing the URL or using developer tools to change page state.
5. At mobile width, follow a tag and run a space-scoped search. Result links retain the space and the document has no horizontal overflow: [mobile search](space-search-mobile.jpg).
6. Disable website delivery: [disabled publication](publication-disabled.jpg). An independent unauthenticated HTTP probe returns 404 for the space and its document list, 503 for the still-unexpired previously issued public image, and 200 for the default space. [HTTP results](withdrawal-http.json) omit the opaque token.
7. Visit the withdrawn space in the still-authenticated browser: it shows the public 404 page. Return to management and open the same public source file; the existing content remains readable and the editor shows saved state.

The screenshots were saved from the exact image bytes emitted by the browser tool and inspected after saving. All four containers, both disposable volumes and the compose network were removed after the run.

## Validation and limits

- `frontendCheck`: 66 tests passed, formatting and type checks passed, production build passed.
- Real PostgreSQL/HTTP publication integration passed, including owner authorization, CSRF, response identity and different content/images at identical paths in two repositories.
- Native Linux storage suite: 219 tests passed, zero skips, aborts or failures. Includes anonymous rejection of a private image token and member access independent of website delivery.
- Applicable Java style, module-boundary and repository-document checks passed.

This is local integration evidence, not proof of production deployment, HTTPS operation, repository-provider provisioning, large-catalog capacity or completion of cross-workspace discovery and collection navigation. Do not merge this artifact branch into the product branch.
