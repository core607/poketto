# Public author signatures

The screenshots show the actual Spring application, PostgreSQL, synthetic Git repositories and production Next.js frontend behind Caddy. The demonstrated source is `5cfb630361726ed1882f14e2a2142b0c9b4c2fe9`; the subsequent base alignment at `cece5cbea6e97f2c2d6ccd2c927bfc6e35e0ced3` has an identical Git tree.

The application was staged with `./gradlew stageAcceptanceRuntime` and started with `docker compose -p poketto-authors --env-file .gradle/authors-acceptance.env -f acceptance/compose.yaml up --build -d`. It used two disposable local repositories, the actual owner login and CSRF flow, and a fresh database. No provider or production credentials were used. The browser was the Codex in-app browser at its default desktop viewport; the original captures are retained without editing.

The owner opened Website publication, entered the workspace public signature `窗边编写组`, and saved it through the UI. The acknowledged setting then appeared on the unsigned album. The separately authored article retained its own `雨后散步者` signature from `public_author`, even after the workspace change. Its private `author` sentinel was absent from the public page.

| Capture | Observed result | SHA-256 |
|---|---|---|
| [Saved workspace signature](workspace-signature.jpg) | Authoritative saved receipt and the selected signature | `abd1641e267c090e08bb37b0da9b74d16c2e4a53616f3245d2381f4c37f6c22c` |
| [Unsigned album](album-fallback.jpg) | Album heading uses the workspace signature | `7b6a7ea4cbf7d60796803268816f18a02bb3d5f127ddc7bce3a5dc0d7a5052f8` |
| [Authored article](article-signature.jpg) | Article heading keeps its explicit signature | `16d07e532024b913549e9fe7060b7583bad69814091779e9797acde723c29d6a` |

The captures were made on 2026-09-14, 06:37–06:38 Asia/Hong_Kong. A separate 390-pixel viewport DOM check found no horizontal document overflow; no mobile screenshot is used as visual evidence. This local HTTP run does not establish production HTTPS behavior, provider interoperability or actual MCP-client acceptance. The artifact branch is not a product dependency and must not be merged into the application branch.
