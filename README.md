# Browser move acceptance

Demonstrated tree: `45327bc03e930e0c7401a7853b6e5ae45324cbe6`, clean and unchanged during capture.

Start: `./gradlew stageAcceptanceRuntime`, followed by `docker compose -p poketto-moves-final --env-file acceptance/.env -f acceptance/compose.yaml up --build -d` (the backend runtime is unchanged from the previous build).

Dependencies: actual Spring application, PostgreSQL 17, production Next.js and Caddy; fresh isolated synthetic repository and volumes. No mocked application or repository APIs. Browser: Codex in-app Chromium, 1265 x 712 viewport.

The single continuous 7.8-second capture shows Esc cancellation with focus restoration, then an authority conflict after a second real browser client saves an additional paragraph. That second tab is outside the recording; its successful save is asserted before the recorded move is submitted. The stale picker closes and refreshes the directory while retaining the previously expanded folder. Reselecting the folder, correcting an existing-name collision, and choosing a destination succeeds. Opening the inbound document shows both its repaired link and the other client's retained paragraph. The folder changes from `随记` to `手册/散步`.

Opening the initial file automatically expands its ancestor folder. Browser assertions verified the cancel trigger, successful concurrent save, closed stale dialog, retained directory expansion, collision error, editor focus fallback, exact decoded repaired destination `手册/散步/雨后.md`, and preservation of the concurrent paragraph.

The GIF retains all 16 frames at their measured capture intervals (approximately 450 ms); it is sampled screen recording, not full-frame-rate video. It demonstrates neither CLI operation, the planned repository format conversion nor exhaustive media/authorization cases. Capture and encoded artifact were inspected for legibility and sensitive data.
