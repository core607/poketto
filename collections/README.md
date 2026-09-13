# Collection reading: browser evidence

Demonstrated application and frontend revision: `5f9ad641d8fefcb5cf5efb3d4c524d68750b7a97`. Captured on 2026-09-14 (Asia/Hong_Kong); later product edits only update documentation.

Started with `./gradlew stageAcceptanceRuntime` and `docker compose --env-file <ignored disposable environment> -p poketto-collection -f acceptance/compose.yaml up -d --build`. Real Spring, PostgreSQL 17.11, production Next and Caddy used two independent synthetic Git repositories and a disposable owner. The final run started with fresh volumes. Provider provisioning was not exercised. No production data or provider credentials were used.

Browser: Codex in-app browser. [Choose a collection](choose-collection.jpg), captured at 04:15:01, uses a temporary 1280 by 720 viewport to check the wide layout. [Final entry on mobile](mobile-final-entry.jpg), captured at 04:15:29, uses 390 by 844; the document scroll width is 375 with no horizontal overflow. Native scrollbars affect captured content width. These are separate unmodified screenshots from one final run; saved bytes were reopened and inspected.

## Observed flow

1. Open the space's root landing and follow its authored first-article link. The article URL carries the root collection and shows first-of-two state with a next link.
2. Follow Next. The same collection remains selected; the final article offers Previous and explicitly ends the sequence. Its repeated opening title is absent, while a DOM read confirms the original heading anchor remains. The other article's distinct opening heading remains visible.
3. Browser Back restores the first article and its collection. Return to collection opens the original landing. Expanding its directory shows the two articles in authored order.
4. Open the writing article directly. It lists both memberships without selecting either. Choosing the handbook changes the same article from second-of-two in the root collection to first-of-two in the handbook, with a different next article.
5. Supply an external URL as the collection parameter. The page offers only verified memberships and generates no external return link.
6. At mobile width, choose the root collection and inspect the final-entry layout. Press Enter on Previous and verify navigation to the first article with the root context intact.

The viewport override was reset and the browser tab retained. The disposable application containers, volumes and network were removed after the run.

## Validation and limits

`frontendCheck` passes 67 tests, formatting, types and the production build. Real PostgreSQL/HTTP collection and publication integration, Java style, public-document mapping, module-boundary checks and 219 native Linux storage/asset regressions pass. Rendering coverage preserves original fragments, later/distinct headings and previews.

This is local integration evidence, not production delivery, large-catalog capacity, README folder fallback, album thumbnails, author metadata, complete site search or search-return scroll restoration. Do not merge this artifact branch into product history.
