# Browser move acceptance

Demonstrated tree: `13e1e2649f82f4955bb8096a68915b9da4762e46`, clean and unchanged during capture. The product PR adds only delivery documentation after this recording.

Start: `./gradlew stageAcceptanceRuntime` followed by `docker compose -p poketto-moves-13e1e26 --env-file acceptance/.env -f acceptance/compose.yaml up --build -d`.

Dependencies: actual Spring application, pinned PostgreSQL 17, production Next.js and Caddy; fresh isolated synthetic repository and volumes. No mocked application or repository APIs. Browser: Codex in-app Chromium, 1265 x 712 captured viewport.

The single continuous 6.6-second capture shows Esc cancellation with focus returned to the folder trigger, a rejected existing destination, editing the name, selecting a destination folder, successful movement, and opening the inbound document with its repaired link. The move changes `随记` to `手册/散步`. Browser assertions also verified the restored trigger, enabled corrected submission, successful editor focus fallback and the exact decoded repaired destination `手册/散步/雨后.md`.

The GIF preserves all 15 captured frames and their capture intervals (approximately 400 ms). It is a sampled screen recording, not a full-frame-rate video. It does not demonstrate the CLI, the planned public/private format migration, or exhaustive media and authorization behavior; those require their own coverage. Capture and encoded artifact were inspected for legibility and sensitive data.
