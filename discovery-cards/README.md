# Discovery album cards browser evidence

- Demonstrated source: `e0ca2b96d1b89c99a08c10034509034f6a2bfb84`.
- Feature parent: `a4827d2552ef1e005630ffd6f8bd09830e290321`; the demonstrated tree is equal to that feature tree after main alignment.
- Captured: 2026-09-14, Asia/Hong_Kong.
- Stack: `./gradlew stageAcceptanceRuntime`; `docker compose --env-file .gradle/discovery-album-cards.env -p poketto-discovery-cards -f acceptance/compose.yaml up --build -d`.
- Services: real Spring acceptance application, PostgreSQL 17.11, production Next build and Caddy on `http://127.0.0.1:38286`.
- Fixture: two independently seeded synthetic Git workspaces (`Default workspace` and `阅读室`); second website enabled through the owner UI and assigned the disposable public signature `阅读室公开署名`. The seed contains an article, valid `相册/` and `手册/` folder landings with inline/valid/nested/private/unreadable media, and a broken-only `故障相册/` landing. No production data, provider credentials or credentials are included here.
- Cleanup: compose containers, volumes and network were removed after the run. Browser tabs were left open and the temporary viewport was reset.

## Raw screenshots

These are unchanged JPEG bytes emitted by the browser tool. They were not cropped, composited or annotated.

| File | State | Viewport image | SHA-256 |
| --- | --- | --- | --- |
| `desktop-top.jpg` | Mixed batch hero, article and valid album cover | 1905x938 | `2C5EF4CE074F7BC306BC283E5BD3219F561F22B8B9FA61263520FE3EF0EFAA7F` |
| `desktop-lower.jpg` | Broken-cover placeholder, valid covers, public signature and overlap labels | 1905x938 | `C8FD3B6BB6D88506AF186DDF5A1804BD96D4312925E1D3BAC05F40643B18EE1D` |
| `mobile-top.jpg` | Same mixed batch at temporary mobile viewport | 375x812 | `C7BC02CE781315FBD7FEB98FA191CF1DADF800E9165BB5C4C9C985D33DBE09E8` |

## Assertions

- A fresh discovery batch showed cards from both workspaces. It included an ordinary article, album and collection cards, and overlapping `相册 · 合集` cards.
- The second workspace's public signature appeared on its cards. Article-level public author text remained visible where authored.
- Valid covers loaded as 320-pixel-wide images. Every observed `img.src` was same-origin and began with `/api/public/assets/`.
- The broken-only album showed `封面暂时不可用 · 打开相册 ↗`, kept its folder link, and rendered no image or original fallback.
- A valid album entrance opened through the card link and displayed the authored collection directory, collection-reading navigation and gallery, including the malformed gallery preview's unavailable state.
- Opening an article and pressing Back preserved the exact batch URL and six-card order.
- Reloading the same batch preserved card order while issuing fresh cover grant URLs.
- With a temporary viewport of 390x844, `innerWidth=390`, `innerHeight=844`, and document/body `scrollWidth=375`; no horizontal overflow was observed.

## Bounds and limits

The run exercised the visible-page enrichment limits in the demonstrated source: at most 32 spaces per batch, four sampled entries per space, six visible cards per page, eight cover candidates per folder and a 32 MiB cover source-read allowance. It is isolated local HTTP evidence with synthetic repositories; it does not prove production HTTPS, production data, repository-provider provisioning, large-catalog capacity or deployment behavior.
