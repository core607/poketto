# Final OAuth consent acceptance

Source: `d57ad8c49b423a2db0bf72472e53c314c8f2d3bc`. Frontend image configuration digest: `sha256:69fc49efd803d4f1dce51812eb8208f79723dad0880c94a456c734eabf491f7a`.

The isolated Compose project and volumes were recreated before this run. Start commands: `gradlew stageAcceptanceRuntime`, then `docker compose --env-file .gradle/oauth/fixture.env -p poketto-oauth-acceptance -f acceptance/compose.yaml -f .gradle/oauth/override.json up --build -d`. The override sets the synthetic OAuth HTTPS issuer; transport is loopback HTTP. PostgreSQL, Spring, production Next.js and Caddy are real; Git/media data and the owner account are disposable fixtures.

Chrome owner login opens the real consent page. Execution and refresh start selected, while private reading, private writing and publication remain unchecked. The native browser capture is 1545 by 945 pixels and was not edited. It contains no production data or credentials.

After capture, the owner approved the default scopes. PKCE code exchange and refresh succeeded; the API-key page showed zero static keys while the application-connection page showed the new connection. Browser disconnection changed its state to disconnected; subsequent access returned 401 and refresh returned `invalid_grant`.

The screenshot establishes the consent layout and default choices, with the later actions supported by browser and protocol readbacks. It does not prove public TLS or actual Claude/ChatGPT interoperability. The nonresolving callback is consumed by a local protocol helper, not by an external client.

![Explicit owner consent](consent.jpg)
