# Isolated application acceptance

This entrance runs the actual Spring services, PostgreSQL, production Next.js build, and same-origin Caddy routing against a synthetic Git repository. It never reads the operator's repository configuration. The fixture application is compiled from integration-test sources and is absent from the production application image.

Use Java 26, the pinned frontend runtime, and a working Docker daemon. From the repository root, run `./gradlew stageAcceptanceRuntime`. Copy `acceptance/.env.example` to ignored `acceptance/.env`, supply a fresh disposable password and the tested source revision, then run:

```sh
docker compose --env-file acceptance/.env -f acceptance/compose.yaml up --build -d
```

Open `http://127.0.0.1:38180`; log in as `owner` with that disposable password. If the port changes, update both the port and origin settings. The repository includes ordinary Chinese Markdown paths, optional metadata, a folder gallery, a private sentinel, and an excluded sentinel. Exercise edits, moves, conflicts, managed uploads, image previews, membership and key operations through the real browser. Obtain MCP keys through the same administration interface.

The application refuses to seed a nonempty fixture root. Each fresh run requires disposing of this stack's sample volumes first:

```sh
docker compose --env-file acceptance/.env -f acceptance/compose.yaml down --volumes
```

These volumes contain only this entrance's samples. They are separate from production deployment directories. A stopped run may be inspected before disposal; do not treat this seed-and-dispose entrance as a production restart strategy.

Local HTTP acceptance does not satisfy the phase-one requirement for the final HTTPS domain, real content corpus, or both actual MCP clients. The production executor is also absent until its separate service and signed-lease configuration are supplied. Record the source revision and real screenshots alongside each completed browser scenario; a successful container start alone is not acceptance.
