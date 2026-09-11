# Operator Administrator Setup

Date: 2026-09-11

## Decision

The first site administrator is created through an interactive command in the deployed application container. This replaces token-based browser initialization from [workspace identity](2026-09-06-workspace-identity-http.md) and implements the installation boundary in [multi-user delivery](../proposed/2026-09-11-multiuser-workspaces-and-discovery.md). Account registration and workspace invitations remain separate user flows.

`./deploy.sh --initialize-admin` reads the deployment's literal configuration under its normal deployment lock and executes `java org.springframework.boot.loader.launch.JarLauncher admin init` in the running application container. It requires an interactive terminal and rejects simultaneous image, sync, or stdin configuration changes. It does not pull images, replace containers, or rewrite configuration.

The application recognizes `admin init` before starting Spring HTTP services. The command reads `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, and `SPRING_DATASOURCE_PASSWORD` from its process environment. The application must already have initialized the database schema and default workspace. A custom container installation can invoke the same Java command with `docker exec -it <app-container> ...`; it must use the running installation's existing environment.

Java Console prompts for the login name and two hidden password entries. Password arguments and piped input are rejected. The command uses the shared account normalization and password encoder, and clears its input character arrays afterward. Database failures and validation failures print fixed messages rather than exceptions that could expose connection credentials.

The existing durable initialization singleton is locked in the account-creation transaction. Exactly one account becomes site administrator and default-workspace owner; concurrent commands cannot create a second administrator. A completed installation is rejected before asking for credentials. Cancellation, invalid input, and mismatched passwords create no account. This command cannot recover passwords, replace an owner, or reopen initialization.

There is no browser initialization controller or login-page initialization option. Deployment configuration does not accept or forward `POKETTO_AUTH_INITIALIZATION_TOKEN`; remove that key from literal deployment `.env` files before installing the updated script. Existing account rows, memberships, and login credentials are preserved.

## Alternatives and consequences

A public setup form makes installation controls look like ordinary account registration and requires a separate bootstrap secret to secure them. Hiding its link would leave the endpoint available. Operator-side database authority supplies the boundary without an anonymous initialization credential.

Default passwords and command-line password arguments can escape into logs or shell history. Interactive hidden input makes the credential entry explicit and keeps secrets out of arguments. Automated CI deployment never attempts to prompt: first administrator creation is a separate operator action.

## Verification

PostgreSQL tests cover account creation, password login, array clearing, cancellation, validation failure, repeat rejection before prompting, and durable concurrent initialization. HTTP tests prove the initialization route is absent and retain login/session/CSRF checks. Streaming body-limit coverage uses the registration endpoint and verifies no account or invitation consumption occurs before complete body validation. Deployment tests reject noninteractive setup and combinations with deployment mutations.
