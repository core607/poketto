# README galleries and lightbox: browser evidence

Demonstrated source: `fc3f581d4b6dd53aac70bc348b49d43d5834caa5`. Captured on 2026-09-14 (Asia/Hong_Kong). Later product changes only update documentation.

Start mode: `./gradlew stageAcceptanceRuntime`, then `docker compose --env-file <ignored disposable environment> -p poketto-albums -f acceptance/compose.yaml up -d --build`. Real Spring, PostgreSQL 17.11, production Next.js and Caddy use synthetic repositories and a disposable owner. The fresh backend was staged at `e1ea854`; its application and fixture sources are identical at the demonstrated commit. The final frontend was rebuilt after the CSS-only centering correction. No production content or provider credentials were used.

The Codex in-app browser followed the authored album link from the space landing to a README-only folder. Its title, collection context and two gradient images came from the synthetic repository. A third image has a permitted JPEG header but no pixel stream, so the browser exercises its actual decode-error path.

- [Desktop lightbox](desktop-lightbox.jpg), 05:08:59, 1280 by 900: second image, counter and both navigation buttons. Arrow keys and mouse controls follow gallery order. Tab and Shift+Tab wrap between dialog controls; a boundary arrow preserves the final image and focus.
- [Image error](image-error.jpg), 05:09:04, 1280 by 900: the third image reports failure while Previous and Close remain usable. Previous returns to a readable image. Escape restores focus to the original first-image opener even after navigating within the dialog.
- [Mobile lightbox](mobile-lightbox.jpg), 05:09:16, 390 by 844: the second-image opener launches a centered dialog with visible image and controls. Document scroll width equals the 390-pixel viewport; the dialog is 356 pixels wide. Close restores focus to that second-image opener.

Screenshots are separate, unmodified captures; saved bytes were reopened and inspected. The viewport override was reset and the browser tab retained. Disposable services and volumes were removed after acceptance.

## Validation and limits

`frontendCheck` passes 67 tests, formatting, types and production build. Real PostgreSQL/HTTP publication integration covers README fallback, index precedence, excluded-index behavior, folder links and unchanged raw source. Java style, module/public-controller checks, repository validation and 219 native Linux asset/storage tests pass. One earlier concurrent native run timed out in an existing five-second monitor test; an unchanged-source rerun passed all 219 after the concurrent builds ended. No timeout or test assertion was relaxed.

This evidence does not establish production rollout, thumbnails, album discovery cards, author metadata or complete site search and return-state behavior. Image URLs still deliver original bytes. Do not merge this artifact branch into product history.
