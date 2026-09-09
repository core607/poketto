# Indexed media browser acceptance

Source tree: `6763d6f502b9d744a2a5c1f936451c003a4d0f3e`, clean and unchanged during capture.

Start: `./gradlew stageAcceptanceRuntime`, then `docker compose -p poketto-media-6763d6f --env-file acceptance/.env -f acceptance/compose.yaml up --build -d`. Actual Spring, PostgreSQL 17, production Next.js and Caddy run against fresh synthetic Git and local immutable originals. Fixture setup uploads a PNG and PDF through the real raw media API and saves the logical index with its referring Markdown through the atomic patch API. No mocked HTTP responses or private user content are used.

Codex in-app Chromium, 1265 x 712 viewport. The actual image loaded at 320 x 180. The single 5.3-second sampled recording retains all ten capture intervals while the PDF link is clicked. A separate click on the same unchanged page, observed with the browser download event API, confirmed a download event. Attachment downloads leave the page visually unchanged; the recording demonstrates the rendered image and correct download entrance, not an operating-system save dialog.

The in-app browser does not expose the downloaded filesystem path. An independent real HTTP read of the exact linked URL verifies every byte against the uploaded PDF, its SHA-256, attachment disposition, octet-stream type, nosniff and no-store. Anonymous private access and a guessed public URL for the private indexed path were denied. `download-proof.json` retains these checks. The original-file transfer admission test separately holds real streams to verify two operations per workspace, four per instance, rejected input remains unread, and capacity is reusable after completion.

This does not demonstrate CodeAct materialization, ZIP export or live repository migration. Those belong to the larger content plan. The rendered artifact was inspected for legibility and sensitive content.
