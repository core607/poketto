# Browser export acceptance

Tested source: `829153d28e55b78065a46c2277a83418252040e8` plus `source.patch`, subsequently committed unchanged as `ea804633e7db40084b09dd076c04a13cb1102ec6`.

The real application ran through `acceptance/compose.yaml`: Spring, PostgreSQL 17, production Next.js, Caddy, a fresh synthetic remote Git repository and native Linux managed originals. The isolated stack used a loopback origin and disposable owner credentials. No production repository or model was used.

Chrome 153.0.8010.36, viewport 1440 x 1050. `export-flow.webm` is one continuous recording. The GIF samples that recording at two frames per second and scales it to 1152 x 840. Decoded frames and the final error screenshot were inspected for legibility and sensitive content.

The recording demonstrates file cancellation and focus restoration, public ZIP download with unsaved editor text retained, refusal of a private selection in public scope, recovery by choosing a private copy, folder/workspace cancellation, and a private missing-attachment error that does not ask the user to publish. Independent ZIP inspection verifies original bytes, relative links, metadata removal and exclusion of unsaved text. `checks.json` records results and hashes.

Frontend formatting, types, 43 tests and production build passed. The browser timeout now permits the server's maximum configured 600-second build plus response headroom; this recording does not simulate a ten-minute build. The sample containers and volumes were removed after acceptance. This local fixture does not establish production HTTPS, actual-user corpus or CLI acceptance.
