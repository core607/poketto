# Pocket games

A game is a public introduction article and a declared JavaScript package. Its
`小游戏` tag is assigned by the platform after validation; authored copies of that
tag are ignored without changing the Git source. Ordinary articles remain readable
when their game is invalid or unavailable. Start with the
[pocket lantern template](../examples/pocket-game/README.md), which owns the author
protocol and package limits.

## Publication and validation

Publish the introduction with a unique frontmatter UUID and place its manifest
alongside it as `<article-filename>.game.json`. All declared files must be public
under the same snapshot's publishing policy. The background catalogue checks the
manifest, paths and bytes, then runs initialization and observation in the game
worker. It does not execute a package on an anonymous request. Validation means
protocol conformance, not trust in the author's code.

In website settings, open **小游戏接入检查** to read recent results. This requires
publication permission. `INVALID_PACKAGE` describes invalid declarations or files;
`INVALID_GAME` describes initialization, observation or execution-limit failure;
`GAME_CAPACITY` and `GAME_UNAVAILABLE` wait for capacity or service recovery. The
bounded diagnostic cache retains 256 entries across spaces. A missing entry is not
proof that the article passed or failed. Unchanged invalid rules are not executed
again while their rejection remains cached; changing package bytes permits a new
check.

The catalogue retains at most 64 games and 16 MiB of encoded packages. Each cycle
scans at most 32 articles in one published space, five seconds after the preceding
cycle. Previously known games receive priority when a snapshot changes. Closed
websites release catalogue capacity; extra candidates wait rather than evicting
live games. Validation is asynchronous, so a new snapshot can temporarily lack its
marker. Public lists, search, plaza reads and article pages add the marker only for
the currently validated snapshot. Neither scans nor reads fetch remote Git.

Game versions hash the manifest and declared file bytes. Unrelated Git edits do not
change that version, though current publication must still be verified. External
Git changes take effect after normal synchronization observes them.

## Browser and agent play

Public article pages offer **开始游戏** without login. A fixed iframe has an opaque
origin and starts a fresh, terminable worker for each move. Rule code receives no
DOM, login state or platform API. The optional presentation returns text, action
buttons and a declared raster image. Rendering never inserts authored HTML.

Before each local step the parent checks current package availability through a
small public status response. Withdrawal, version changes and connection failures
stop further moves in this interface while preserving the last progress. Downloaded
code cannot be recalled from an offline or modified client. Anonymous play starts
no server execution job; it makes only public package/status reads. Collapsing a
game keeps its temporary state on that page; navigating away discards unsaved state.

The [plaza](plaza.md) supplies account game commands. Every command requires current
creator eligibility and a key its holder issued:

| Command | Result |
| --- | --- |
| `peek` | Up to 20 account saves and `nextCreationRequest` |
| `play <space/route> <nextCreationRequest>` | Create a game and return its first observation |
| `peek <save-UUID>` | Observe the selected save |
| `press <save-UUID> "<action>" <revision>` | Apply one offered action and return its new observation and revision |
| `peek --remove <save-UUID>` | Delete an account save; return `DELETED` or `ABSENT` |

Returned observations include suggested complete `press` commands. Author action
names are data within that selected game; they cannot replace platform commands.
There is no persistent game shell. Every server initialization, observation or
move runs as a finite isolated job. Game words are untrusted and cannot become
platform status, candy, comments or permissions. Playing consumes no candy and
invokes no model by itself.

## Saves and handoff

Registered accounts can select **管理账号存档** and explicitly save, load or delete
progress. Browser saves and loads run no game code on the server. Each account can
retain 20 saves shared with the assistants on its own keys; other accounts cannot read
them. To hand off, save on the website and let the assistant use `peek` and `press`.
Use **载入并替换当前进度** to resume the assistant's latest version in the browser.
Saving is manual; loading explicitly replaces current local progress.

Progress is untrusted JSON, bounded to 32 KiB, 16 levels and 2048 object/array
entries. It grants no rewards or certified scores. HTTP save bodies are capped at
128 KiB before JSON parsing, including escaped text. Cloud writes bind the account,
workspace, article, package version and expected save revision. A stale browser or
assistant receives `SAVE_CONFLICT`; load the latest save or save a separate new
game instead of silently overwriting it. An updated package suspends older saves
with `GAME_UPDATED`; automatic migration is unavailable. Withdrawn or failed games
can still be removed from the account.

Creation uses an account's monotonic request number; moves use the expected
revision and exact action. Retry an uncertain request with the same inputs. The
browser's retry button retains the original payload even if local play continues.
Deleting a save does not make its creation number reusable. No lifetime save-count
limit or per-deletion tombstone is required. Failed initialization retains a failed
save that can be removed before starting another game.

Execution occurs outside database transactions. Admission reserves a finite attempt
token, and completion rechecks the key, publication, package and revision before
writing. A late completion cannot replace another attempt after expiry. Revocation
terminates affected jobs and prevents their result from becoming a saved move.

## Deployment

Games are disabled unless `poketto.games.enabled=true`. Configure
`poketto.games.socket` and `poketto.games.max-jobs` (default 2, range 1–16).
The application must run on Linux. Disabled games have no active marker or
execution entrance; stored saves remain in PostgreSQL. This switch is independent
of plaza interactions.

Run a dedicated [Sandcrab service](../executor-service/rust/sandcrab/README.md)
with only the `games` profile, no durable volumes and no host calls. The application
checks these constraints and the native inventory endpoints at startup. Its peer
UID must match `client_uid`; it needs access to the root-owned socket, not a signing
key, root privileges or a Docker socket.

`./gradlew stageGameWorker` stages the static `sandcrab` binary and example host
configuration. Install the binary in a protected root-owned directory and use the
[profile](../executor-service/game-config.example.toml),
[service](../executor-service/sandcrab-games.service) and
[slice](../executor-service/pokettogames.slice) with the pinned toolchain described
by Sandcrab's installation reference. Create a separate unprivileged game account.
Adjust the profile's paths, client UID/GID and account for the installation; the
bundled application defaults to UID/GID 10001. Keep the configuration root-owned
mode 0600 with protected ancestors. The service's unit prefix must equal its
supervisor unit name, without `.service`, followed by `-`.

Each step uploads the application's entry program, the same rule runtime served
to browsers, and bounded game input as one tar archive into a fresh session.
The host needs no game scripts or repository mounts. The command replaces its
shell with Node, so completion terminates the session and descendants. Input stays
bounded to 512 KiB; the package including runtime code is at most 1 MiB.

With the bundled deployment entrance, set `POKETTO_GAMES_ENABLED=true`,
`POKETTO_GAMES_RUNTIME_DIR_HOST` and optionally `POKETTO_GAMES_MAX_JOBS` in `.env`.
`deploy.sh` adds [the games overlay](../deploy/compose.games.yaml) after
`sandcrab-games.service` is active, its root-owned runtime directory is mode 0751,
and its root-owned socket is mode 0660 with the application's group. Existing
installations set the application properties in their own Compose file; their
settings channel accepts no host-prerequisite keys.

Use a different socket, runtime directory, execution account and resource slice
from the repository worker. One application exclusively owns the game service
through `game-service.lock` in `poketto.data-dir`; instances using that service must
share this file and preserve it across restarts. Recovery closes unowned ephemeral
sessions and releases their receipts. Never share this service with another
consumer. No containment failure falls back to a host process.

The example allows two jobs with five-second commands, 15-second sessions,
128 MiB RAM, 48 tasks, 50% CPU, 2 MiB temporary storage and 96 KiB output per job.
The independent slice caps aggregate use at 384 MiB, 128 tasks and 100% CPU with
swap disabled. Failed containment or uncertain admission retains local capacity
until reconciliation or service restart confirms release. Revocation cancels the
matching application-owned session; the daemon receives no account or workspace
identity. Separate admission protects repository capacity, while shared-host CPU
can still increase latency.

## Verification

`GameSavesIntegrationIT`, `GameCatalogueTests`, `GameContentSnapshotsTests`,
`IsolatedGameRunnerTests` and the frontend game tests cover their respective
contracts. `GameSandboxJobTests` covers uncertain admission and cleanup; native acceptance
checks actual cancellation, recovery and retained capacity. Run the [native probe](../executor-native/README.md) with `--scenario
games` to check real containment and mixed workload behavior. Browser acceptance
must separately exercise opaque-origin execution, withdrawal, account save
conflicts and browser/agent handoff; DOM tests do not prove browser isolation.

For the authenticated HTTP/MCP path, stage the acceptance runtime and manifest as
described in [client acceptance](../acceptance/clients/README.md). Keep
`executor-native/game_fixture.py` beside its staged probe tree. Add `--games` to
`native-host.py` to seed the example and start its separate game supervisor, then run
`games-http.py --fixture FIXTURE_ROOT` on that host. Require `gamesHttp: PASS` and
the controller's cleanup result. This verifies real account services, saves and
native jobs, but does not execute the browser iframe. `--origin` can select a
same-origin browser gateway when it differs from the controller's loopback API.
