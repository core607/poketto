# Isolated application acceptance

This entrance runs the actual Spring services, PostgreSQL, production Next.js build, and same-origin Caddy routing against a synthetic Git repository. It never reads the operator's repository configuration. The fixture application is compiled from integration-test sources and is absent from the production application image. Its pinned Linux JDK is shared with the native storage verification entrance.

The [content-root browser run](evidence/2026-09-10-content-root-switching.json)
verifies private-by-default creation guidance, category-preserving root selection,
cancel/focus behavior, refusal of a single-file publication with private dependencies,
and a folder round trip through public and private roots. An indexed local original
keeps its identity and exact bytes; withdrawal removes the public article and
invalidates the old media URL. The receipt records the exact runtime source and
recording hashes. It uses a synthetic workspace, not the production content corpus.

The [integrated root-switching replay](evidence/2026-09-11-content-root-switching.json)
repeats the browser flow with the browser/CLI export features present. Its
[recording and provenance](https://github.com/core607/poketto/blob/24f609639d42e62cd9cdd17e04a548301b88fded/2026-09-11/README.md)
include dependency refusal, exact original downloads and withdrawal checks.

The [portable-export HTTP run](evidence/2026-09-10-export-http.json) uses real
Spring authentication, PostgreSQL, synthetic remote Git and native local originals.
Independent ZIP inspection verifies original bytes, relative links, private
frontmatter retention and public metadata removal. It also covers CSRF and
anonymous rejection, the browser/MCP identity boundary, publication invalidation,
release and complete fixture cleanup. This is backend acceptance; it does not
exercise browser export controls or the CLI export command.

Use Java 26, the pinned frontend runtime, and a working Docker daemon. From the repository root, run `./gradlew stageAcceptanceRuntime`. Copy `acceptance/.env.example` to ignored `acceptance/.env`, supply a fresh disposable password and the tested source revision, then run:

```sh
docker compose --env-file acceptance/.env -f acceptance/compose.yaml up --build -d
```

Open `http://127.0.0.1:38180`; log in as `owner` with that disposable password. If the port changes, update both the port and origin settings. The repository includes ordinary Chinese Markdown paths, optional metadata, a folder gallery, a private sentinel, and an excluded sentinel. Exercise edits, moves, conflicts, managed uploads, image previews, membership and key operations through the real browser. Obtain MCP keys through the same administration interface.

Set `POKETTO_ACCEPTANCE_MULTIPLE_WORKSPACES=true` to add a second space with a separate synthetic Git authority owned by the same account. Use its workspace selector and open each space in a separate tab to verify route-bound edits. This fixture seeds repositories directly; it does not validate GitHub/CNB provisioning credentials.

For managed-credential browser flows, set `POKETTO_ACCEPTANCE_MANAGED_CONNECTIONS=true` and supply a fresh Base64-encoded 32-byte `POKETTO_ACCEPTANCE_CREDENTIAL_KEY`. The integration-only provider fixture accepts `https://github.com/example/acceptance`, username `fixture`, and synthetic tokens `fixture-token-initial` or `fixture-token-replacement`; other credentials fail validation. Creation, authorization, encryption, relational binding and rotation compare-and-set use the real services. Provider metadata and Git credential validation are mocked, and content uses the local synthetic repository. This mode must not be cited as real GitHub/CNB interoperability evidence. It is absent from the production application image.

The application refuses to seed a nonempty fixture root. Each fresh run requires disposing of this stack's sample volumes first:

```sh
docker compose --env-file acceptance/.env -f acceptance/compose.yaml down --volumes
```

These volumes contain only this entrance's samples. They are separate from production deployment directories. A stopped run may be inspected before disposal; do not treat this seed-and-dispose entrance as a production restart strategy.

Local HTTP acceptance does not satisfy the phase-one requirement for the final HTTPS domain, real content corpus, or both actual MCP clients. The production executor is also absent until its separate service and signed-lease configuration are supplied. Record the source revision and real screenshots alongside each completed browser scenario; a successful container start alone is not acceptance.

For image-memory admission, stage the current runtime and run `python acceptance/image-memory-smoke.py`. This independent probe starts only the synthetic Linux application and PostgreSQL, uses a loopback port, and generates disposable credentials under ignored `.gradle/`. It applies a two-CPU quota and the deployment JVM heap percentage, reads the actual maximum heap, and stops at 90% of heap or container memory. The scenarios cover maximum 16 MiB images, public and private HTTP authorization, slow HTTP and MCP SSE responses, request rejection, article/preview/inventory degradation, cancellation and disconnect recovery, exact hashes, and an idempotent MCP upload of the original HTTP upload.

The probe writes source hashes, resource samples and results to its `.gradle/image-memory-*` directory, then removes only its uniquely labelled containers and volumes. Failure diagnostics are kept there for inspection. A passing resource sample supports that fixture and pinned runtime; it does not establish safety for every repository workload or replace the final HTTPS and actual-client acceptance.

For a container-headroom comparison, `--memory-mib 1024` keeps the maximum heap at 500 MiB while increasing only the sample container limit. The default 768 MiB sample uses the deployment heap percentage. This comparison does not change deployment configuration.
