package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Synthetic authority/authentication fixture; all execution uses the production adapter and actual root worker. */
public final class ExecutorNativeProbe {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JsonNode config;
    private final WorkspaceId workspace = WorkspaceId.random();
    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal principal = principal();
    private final AtomicInteger released = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicBoolean privateRead =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    private final RepositorySnapshotExports exports;
    private int tests;

    private ExecutorNativeProbe(Path configuration) throws Exception {
        when(auth.authorize(any(), any(), eq(io.github.core607.poketto.auth.Capability.EXECUTE_REPOSITORY)))
                .thenAnswer(call -> new io.github.core607.poketto.auth.WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        io.github.core607.poketto.auth.MembershipRole.OWNER,
                        privateRead.get()
                                ? java.util.Set.of(
                                        io.github.core607.poketto.auth.Capability.READ_PRIVATE,
                                        io.github.core607.poketto.auth.Capability.EXECUTE_REPOSITORY)
                                : java.util.Set.of(io.github.core607.poketto.auth.Capability.EXECUTE_REPOSITORY)));
        config = JSON.readTree(Files.readString(configuration));
        Path master = path("bundle");
        String commit = config.path("commit").stringValue();
        exports = new RepositorySnapshotExports() {
            @Override
            public Export create(AuthPrincipal actor, WorkspaceId selected, Optional<String> requested) {
                try {
                    if (requested.isPresent() && !requested.get().equals(commit)) throw new IllegalArgumentException();
                    UUID id = UUID.randomUUID();
                    Path target = path("exports").resolve(id + ".bundle");
                    Files.copy(master, target);
                    return new Export(id, commit, hash(Files.readAllBytes(target)), Files.size(target));
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }

            @Override
            public PublicExport createPublic(AuthPrincipal actor, WorkspaceId selected) {
                throw new UnsupportedOperationException("native fixture provides full repository exports only");
            }

            @Override
            public void requireCurrentPublic(AuthPrincipal actor, WorkspaceId selected, PublicExport exported) {
                throw new UnsupportedOperationException("native fixture provides full repository exports only");
            }

            @Override
            public void release(UUID id) {
                try {
                    Files.delete(path("exports").resolve(id + ".bundle"));
                    released.incrementAndGet();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }
        };
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !System.getProperty("os.name").equalsIgnoreCase("Linux"))
            throw new IllegalArgumentException();
        var probe = new ExecutorNativeProbe(Path.of(args[0]));
        if (args[1].equals("abandon")) probe.abandon();
        else if (args[1].equals("main")) probe.run();
        else if (args[1].equals("peer-only")) probe.rejectNonRootPeer();
        else throw new IllegalArgumentException();
    }

    private IsolatedRepositoryExecutor adapter(Path socket) {
        return adapter(socket, 8);
    }

    private IsolatedRepositoryExecutor adapter(Path socket, int maxSessions) {
        return adapter(socket, maxSessions, exports);
    }

    private IsolatedRepositoryExecutor adapter(
            Path socket, int maxSessions, RepositorySnapshotExports selectedExports) {
        return new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        selectedExports,
                        mock(io.github.core607.poketto.assets.MediaFileService.class),
                        org.mockito.Mockito.mock(io.github.core607.poketto.content.AuthorizedRepositoryReader.class),
                        org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryPatchService.class),
                        JSON,
                        socket,
                        path("privateKey"),
                        maxSessions,
                        45,
                        8);
    }

    private void rejectNonRootPeer() throws Exception {
        try (var rejected = adapter(path("fakeSocket"))) {
            assertThatThrownBy(() -> execute(rejected, "wrong-peer", "pwd", new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
        }
        Path observation = path("fakeObservation");
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!Files.exists(observation) && System.nanoTime() < deadline) Thread.sleep(20);
        assertThat(observation).exists();
        JsonNode result = JSON.readTree(Files.readString(observation));
        assertThat(result.path("accepted").booleanValue()).isTrue();
        assertThat(result.path("requestBytes").isIntegralNumber()).isTrue();
        assertThat(result.path("requestBytes").intValue()).isZero();
        passed("root-owned-socket-rejects-non-root-peer", "observation", Map.of("accepted", true, "requestBytes", 0));
    }

    private void run() throws Exception {
        rejectNonRootPeer();
        publicProjection();
        selectedSaves();
        uncertainSaveRecovery();
        mediaFetch();
        mediaImport();
        byte[] originalBundle = Files.readAllBytes(path("bundle"));
        try (var executor = adapter(path("socket"))) {
            long start = System.nanoTime();
            var first = execute(
                    executor,
                    "first",
                    "set -eu; git rev-parse HEAD; git log --oneline; cat article.md; "
                            + "test -p \"$POKETTO_BRIDGE/requests\"; test ! -w \"$POKETTO_BRIDGE/responses\"; "
                            + "if python3 -c 'import socket; socket.socket(socket.AF_UNIX)' 2>/dev/null; then exit 99; fi; poketto status",
                    new Cancellation());
            assertThat(first.exitCode()).isZero();
            assertThat(first.commit()).isEqualTo(config.path("commit").stringValue());
            assertThat(first.stdout()).contains(first.commit(), "Synthetic native history", "searchable");
            assertThat(first.stdout()).contains("\"scope\": \"full\"", "\"baseCommit\": \"" + first.commit() + "\"");
            passed("signed-open-through-production-socket-and-signature-checks", "milliseconds", millis(start));
            passed("synchronous-cli-status-retains-unix-socket-denial-and-read-only-replies");
            start = System.nanoTime();
            for (int i = 0; i < 20; i++)
                assertThat(execute(executor, "first", "git rev-parse HEAD; test -f article.md", new Cancellation())
                                .exitCode())
                        .isZero();
            passed("twenty-adapter-session-reuses", "meanMilliseconds", millis(start) / 20.0);
            assertThat(execute(executor, "first", "printf isolated > only-first", new Cancellation())
                            .exitCode())
                    .isZero();
            assertThat(execute(executor, "second", "test ! -e only-first", new Cancellation())
                            .exitCode())
                    .isZero();
            String firstBridge = execute(executor, "first", "printf '%s' \"$POKETTO_BRIDGE\"", new Cancellation())
                    .stdout();
            assertThat(firstBridge).startsWith("/").endsWith("/bridge");
            String quotedBridge = "'" + firstBridge.replace("'", "'\"'\"'") + "'";
            assertThat(execute(
                                    executor,
                                    "second",
                                    "test ! -r " + quotedBridge + "/lock && test ! -w " + quotedBridge + "/requests",
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            passed("same-key-clients-cannot-read-or-write-another-lease-bridge");
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            workspace,
                            "first",
                            Optional.of("f".repeat(40)),
                            "pwd",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(IllegalArgumentException.class);
            passed("same-key-clients-have-separate-pinned-workdirs");
            assertThat(execute(
                                    executor,
                                    "second",
                                    "rm -rf .git/objects; printf changed > article.md",
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThat(Files.readAllBytes(path("bundle"))).isEqualTo(originalBundle);
            control("assert-source-unchanged");
            passed("command-mutations-preserve-authority-bundle-and-source");
            close(executor, "first");
            close(executor, "second");

            var cancel = new Cancellation();
            var cancelled = CompletableFuture.supplyAsync(() -> execute(executor, "cancel", descendant(), cancel));
            control("await-descendant");
            cancel.cancel();
            assertThat(cancelled.get(20, TimeUnit.SECONDS).terminationReason())
                    .isEqualTo(RepositoryExecutor.TerminationReason.CANCELLED);
            control("assert-no-processes");
            passed("callback-cancellation-kills-detached-descendants");
            close(executor, "cancel");

            var revoked =
                    CompletableFuture.supplyAsync(() -> execute(executor, "revoke", descendant(), new Cancellation()));
            control("await-descendant");
            executor.revoked(new AuthRevocation(workspace, Set.of(), Set.of(principal.subjectId())));
            var reason = revoked.get(20, TimeUnit.SECONDS).terminationReason();
            assertThat(reason)
                    .isIn(RepositoryExecutor.TerminationReason.REVOKED, RepositoryExecutor.TerminationReason.CANCELLED);
            control("assert-no-processes");
            passed("revocation-closes-active-process-tree", "reason", reason);
            close(executor, "revoke");
        }

        // A different synthetic key avoids reusing the deliberately revoked key's worker tombstone.
        var secondPrincipal = principal();
        try (var executor = adapter(path("socket"), 1)) {
            var active = CompletableFuture.supplyAsync(() -> executor.execute(
                    secondPrincipal,
                    workspace,
                    "restart",
                    Optional.empty(),
                    descendant(),
                    Duration.ofSeconds(25),
                    new Cancellation()));
            control("await-descendant");
            control("restart-worker");
            assertThatThrownBy(() -> active.get(25, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(WorkerUnavailableException.class);
            assertThatThrownBy(() -> executor.execute(
                            secondPrincipal,
                            workspace,
                            "restart",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            control("assert-no-processes");
            assertThat(executor.execute(
                                    secondPrincipal,
                                    workspace,
                                    "new-after-restart",
                                    Optional.empty(),
                                    "git rev-parse HEAD",
                                    Duration.ofSeconds(3),
                                    new Cancellation())
                            .exitCode())
                    .isZero();
            assertThatThrownBy(() -> executor.execute(
                            secondPrincipal,
                            workspace,
                            "restart",
                            Optional.empty(),
                            "pwd",
                            Duration.ofSeconds(2),
                            new Cancellation()))
                    .isInstanceOf(WorkerUnavailableException.class);
            passed("worker-restart-recovers-full-single-session-capacity-and-rejects-old-client");
        }
        control("assert-no-processes");
        assertThat(released.get()).isGreaterThanOrEqualTo(6);
        System.out.println(JSON.writeValueAsString(Map.of(
                "summary",
                "PASS",
                "tests",
                tests,
                "authentication",
                "synthetic-stub-not-PG-or-MCP-acceptance",
                "adapterClassSha256",
                classHash(IsolatedRepositoryExecutor.class),
                "nativeProbeClassSha256",
                classHash(ExecutorNativeProbe.class))));
    }

    private void mediaImport() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("import"), path("exports"), auth, workspace);
        String initial = fixture.seedMedia(auth, principal, new byte[] {1, 2}, new byte[] {3, 4});
        var reader = fixture.reader(auth);
        byte[] bytes = new byte[256 * 700];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) i;
        try (var executor = new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        8,
                        45,
                        8)) {
            var imported = executor.execute(
                    principal,
                    workspace,
                    "media-import",
                    Optional.empty(),
                    "set -eu; python3 -c \"from pathlib import Path; Path('private/generated.bin').write_bytes(bytes(range(256))*700)\"; "
                            + "poketto media import private/generated.bin --as private/generated.pdf --type application/pdf --key native_import_original_01; "
                            + "poketto media fetch private/generated.pdf; sha256sum private/generated.pdf; "
                            + "printf '[Generated](generated.pdf)\\n' > private/with-media.md",
                    Duration.ofSeconds(35),
                    new Cancellation());
            assertThat(imported.exitCode())
                    .as("import stdout=%s stderr=%s", imported.stdout(), imported.stderr())
                    .isZero();
            assertThat(imported.stdout())
                    .contains(hash(bytes), "\"indexUpdated\": true", "\"indexSource\": \"worktree\"");
            String identity = JSON.readTree(imported.stdout()
                            .lines()
                            .filter(line -> line.startsWith("{"))
                            .findFirst()
                            .orElseThrow())
                    .path("result")
                    .path("assetId")
                    .asString();
            var beforeSave = reader.getFile(principal, workspace, Optional.empty(), ".poketto/assets.json");
            assertThat(beforeSave.commit()).contains(initial);
            assertThat(beforeSave.source().orElseThrow()).doesNotContain("private/generated.pdf");
            var repeated = executor.execute(
                    principal,
                    workspace,
                    "media-import",
                    Optional.empty(),
                    "set -eu; python3 -c \"from pathlib import Path; p=Path('.poketto/assets.json'); p.write_bytes(b' \\n'+p.read_bytes()+b'\\n')\"; "
                            + "before=$(sha256sum .poketto/assets.json); "
                            + "poketto media import private/generated.bin --as private/generated.pdf --type application/pdf --key native_import_original_01; "
                            + "test \"$before\" = \"$(sha256sum .poketto/assets.json)\"; poketto save .poketto/assets.json private/with-media.md",
                    Duration.ofSeconds(35),
                    new Cancellation());
            assertThat(repeated.exitCode())
                    .as("repeat stdout=%s stderr=%s", repeated.stdout(), repeated.stderr())
                    .isZero();
            assertThat(repeated.stdout()).contains(identity);
            var saved = reader.getFile(principal, workspace, Optional.empty(), ".poketto/assets.json");
            assertThat(saved.source().orElseThrow())
                    .contains("private/generated.pdf", "public/manual.pdf", "private/manual.pdf");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/with-media.md")
                            .source())
                    .contains("[Generated](generated.pdf)\n");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/generated.bin")
                            .expectedAbsence())
                    .isTrue();
            var conflictingKey = executor.execute(
                    principal,
                    workspace,
                    "media-import",
                    Optional.empty(),
                    "printf 'changed-original' > private/generated.bin; "
                            + "poketto media import private/generated.bin --as private/generated.pdf --type application/pdf --key native_import_original_01 --replace",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(conflictingKey.exitCode()).isEqualTo(1);
            assertThat(conflictingKey.stdout()).contains("IDEMPOTENCY_CONFLICT");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), ".poketto/assets.json")
                            .commit())
                    .isEqualTo(saved.commit());
            var replacement = executor.execute(
                    principal,
                    workspace,
                    "media-import",
                    Optional.empty(),
                    "set -eu; poketto media import private/generated.bin --as private/generated.pdf --type application/pdf --key native_import_original_02 --replace; "
                            + "poketto media fetch private/generated.pdf --output private/new-version.pdf; cat private/new-version.pdf; "
                            + "poketto media fetch private/generated.pdf --commit "
                            + saved.commit().orElseThrow()
                            + " --output private/historical-version.pdf; sha256sum private/historical-version.pdf",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(replacement.exitCode())
                    .as("replace stdout=%s stderr=%s", replacement.stdout(), replacement.stderr())
                    .isZero();
            assertThat(replacement.stdout()).contains("changed-original", hash(bytes));
            passed("media-import-is-idempotent-preserves-local-index-and-saves-text-index-atomically");
        }
    }

    private void mediaFetch() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("media"), path("exports"), auth, workspace);
        byte[] original = new byte[192 * 1024];
        for (int i = 0; i < original.length; i++) original[i] = (byte) (i % 251);
        byte[] updated = "new-private-original".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String originalCommit = fixture.seedMedia(auth, principal, original, original);
        var reader = fixture.reader(auth);
        try (var executor = new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        8,
                        45,
                        8)) {
            var first = executor.execute(
                    principal,
                    workspace,
                    "media-full",
                    Optional.empty(),
                    "set -eu; test ! -e private/manual.pdf; poketto media fetch private/manual.pdf; "
                            + "poketto media fetch private/manual.pdf; sha256sum private/manual.pdf",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(first.exitCode())
                    .as("media stdout=%s stderr=%s", first.stdout(), first.stderr())
                    .isZero();
            assertThat(first.stdout()).contains(hash(original));
            String latest = fixture.seedMedia(auth, principal, original, updated);
            var historical = executor.execute(
                    principal,
                    workspace,
                    "media-full",
                    Optional.empty(),
                    "set -eu; poketto media fetch private/manual.pdf --commit " + originalCommit
                            + " --output private/history.pdf; "
                            + "poketto media fetch private/manual.pdf --commit " + latest
                            + " --output private/latest.pdf; "
                            + "sha256sum private/history.pdf private/latest.pdf",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(historical.exitCode())
                    .as("media stdout=%s stderr=%s", historical.stdout(), historical.stderr())
                    .isZero();
            assertThat(historical.stdout()).contains(hash(original), hash(updated));
            var preserved = executor.execute(
                    principal,
                    workspace,
                    "media-full",
                    Optional.empty(),
                    "printf 'local-edit' > private/manual.pdf; poketto media fetch private/manual.pdf",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(preserved.exitCode()).isEqualTo(1);
            assertThat(preserved.stdout()).contains("LOCAL_FILE_EXISTS");
            var local = executor.execute(
                    principal,
                    workspace,
                    "media-full",
                    Optional.empty(),
                    "cat private/manual.pdf",
                    Duration.ofSeconds(5),
                    new Cancellation());
            assertThat(local.stdout()).isEqualTo("local-edit");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), ".poketto/assets.json")
                            .commit())
                    .contains(latest);
            passed("full-scope-media-fetch-retains-historical-originals-and-never-overwrites-local-edits");
        }
        privateRead.set(false);
        try (var executor = new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        8,
                        45,
                        8)) {
            var fetched = executor.execute(
                    principal,
                    workspace,
                    "media-public",
                    Optional.empty(),
                    "set -eu; p=$(python3 -c 'import json; print(next(iter(json.load(open(\".poketto/assets.json\"))[\"files\"])))'); "
                            + "poketto media fetch \"$p\"; sha256sum \"$p\"",
                    Duration.ofSeconds(25),
                    new Cancellation());
            assertThat(fetched.exitCode())
                    .as("public media stdout=%s stderr=%s", fetched.stdout(), fetched.stderr())
                    .isZero();
            assertThat(fetched.stdout()).contains(hash(original)).doesNotContain(originalCommit);
            var denied = executor.execute(
                    principal,
                    workspace,
                    "media-public",
                    Optional.empty(),
                    "poketto media fetch private/manual.pdf",
                    Duration.ofSeconds(10),
                    new Cancellation());
            assertThat(denied.exitCode()).isEqualTo(1);
            assertThat(denied.stdout()).contains("MEDIA_UNAVAILABLE").doesNotContain(hash(updated));
            var uploadDenied = executor.execute(
                    principal,
                    workspace,
                    "media-public",
                    Optional.empty(),
                    "printf 'data' > created.bin; poketto media import created.bin --as public/created.bin --key native_public_denied_01",
                    Duration.ofSeconds(10),
                    new Cancellation());
            assertThat(uploadDenied.exitCode()).isEqualTo(1);
            assertThat(uploadDenied.stdout()).contains("READ_ONLY_SCOPE");
            passed("public-media-fetch-uses-only-host-owned-projection-mapping-without-source-history");
        } finally {
            privateRead.set(true);
        }
    }

    private void uncertainSaveRecovery() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("recovery"), path("exports"), auth, workspace, true);
        var reader = fixture.reader(auth);
        try (var executor = new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        8,
                        45,
                        8)) {
            var unknown = executor.execute(
                    principal,
                    workspace,
                    "recover-save",
                    Optional.empty(),
                    "printf 'original-attempt' > private/secret.md; poketto save private/secret.md",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(unknown.exitCode()).isEqualTo(1);
            assertThat(unknown.stdout()).contains("WRITE_OUTCOME_UNKNOWN");
            fixture.restoreTransport();
            var recovered = executor.execute(
                    principal,
                    workspace,
                    "recover-save",
                    Optional.empty(),
                    "printf 'later-local-edit' > private/secret.md; poketto recover; cat private/secret.md",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(recovered.exitCode())
                    .as("recovery stdout=%s stderr=%s", recovered.stdout(), recovered.stderr())
                    .isZero();
            assertThat(recovered.stdout()).contains("\"recovered\": true", "later-local-edit");
            assertThat(fixture.pushes()).isEqualTo(1);
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/secret.md")
                            .source())
                    .contains("original-attempt");
            var saved = executor.execute(
                    principal,
                    workspace,
                    "recover-save",
                    Optional.empty(),
                    "poketto save private/secret.md",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(saved.exitCode()).isZero();
            assertThat(fixture.pushes()).isEqualTo(2);
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/secret.md")
                            .source())
                    .contains("later-local-edit");
            passed("uncertain-cli-save-recovers-original-commit-without-replaying-new-local-edits");
        }
    }

    private void selectedSaves() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("saves"), path("exports"), auth, workspace);
        doAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        var reader = fixture.reader(auth);
        try (var executor = new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        8,
                        45,
                        8)) {
            var saved = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "set -eu; printf 'local-unselected' > AGENTS.md; "
                            + "python3 -c \"from pathlib import Path; Path('private/secret.md').write_bytes(('猫\\r\\n' * 30000).encode()); "
                            + "Path('private/created.md').write_text('new-file')\"; "
                            + "git add .; git -c user.name=Sandbox -c user.email=sandbox@example.invalid commit -qm 'untrusted-local-baseline'; "
                            + "poketto save private/secret.md private/created.md; grep -q local-unselected AGENTS.md",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(saved.exitCode())
                    .as("save stdout=%s stderr=%s", saved.stdout(), saved.stderr())
                    .isZero();
            JsonNode receipt = JSON.readTree(saved.stdout().strip());
            assertThat(receipt.path("ok").asBoolean()).isTrue();
            String firstCommit = receipt.path("result").path("commit").asString();
            assertThat(firstCommit).isNotEqualTo(saved.commit());
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/secret.md")
                            .source())
                    .contains("猫\r\n".repeat(30000));
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "AGENTS.md")
                            .source())
                    .contains("operator-secret-needle");
            var second = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "set -eu; printf 'second-save' > private/secret.md; rm private/created.md; "
                            + "poketto save private/secret.md --delete private/created.md; grep -q local-unselected AGENTS.md",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(second.exitCode())
                    .as("save stdout=%s stderr=%s", second.stdout(), second.stderr())
                    .isZero();
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/secret.md")
                            .source())
                    .contains("second-save");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/created.md")
                            .expectedAbsence())
                    .isTrue();
            fixture.competingWrite(auth, principal);
            var conflict = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "printf 'retain-conflicting-edit' > private/secret.md; poketto save private/secret.md",
                    Duration.ofSeconds(20),
                    new Cancellation());
            assertThat(conflict.exitCode()).isEqualTo(1);
            assertThat(conflict.stdout()).contains("REPOSITORY_CONFLICT");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/secret.md")
                            .source())
                    .contains("second-save");
            var retained = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "cat private/secret.md; cat AGENTS.md",
                    Duration.ofSeconds(5),
                    new Cancellation());
            assertThat(retained.stdout()).contains("retain-conflicting-edit", "local-unselected");
            passed("selected-cli-saves-freeze-chunk-and-commit-real-git-with-host-baseline-and-retained-conflicts");
            var aligned = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "set -eu; poketto sync private/secret.md; poketto save private/secret.md",
                    Duration.ofSeconds(25),
                    new Cancellation());
            assertThat(aligned.exitCode())
                    .as("sync stdout=%s stderr=%s", aligned.stdout(), aligned.stderr())
                    .isZero();
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "AGENTS.md")
                            .source())
                    .contains("externally-updated-guide");
            var unselected = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "poketto save AGENTS.md",
                    Duration.ofSeconds(15),
                    new Cancellation());
            assertThat(unselected.exitCode()).isEqualTo(1);
            assertThat(unselected.stdout()).contains("REPOSITORY_CONFLICT");
            var merged = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "poketto sync AGENTS.md",
                    Duration.ofSeconds(15),
                    new Cancellation());
            assertThat(merged.exitCode())
                    .as("sync stdout=%s stderr=%s", merged.stdout(), merged.stderr())
                    .isEqualTo(1);
            assertThat(merged.stdout()).contains("MERGE_CONFLICT");
            var versions = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "cat AGENTS.md",
                    Duration.ofSeconds(5),
                    new Cancellation());
            assertThat(versions.stdout())
                    .contains(
                            "<<<<<<< LOCAL",
                            "||||||| BASE",
                            "operator-secret-needle",
                            "local-unselected",
                            "externally-updated-guide");
            var resolved = executor.execute(
                    principal,
                    workspace,
                    "selected-save",
                    Optional.empty(),
                    "set -eu; printf 'resolved-guide' > AGENTS.md; poketto save AGENTS.md; "
                            + "rm private/secret.md; poketto sync private/secret.md; test ! -e private/secret.md; poketto save --delete private/secret.md",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(resolved.exitCode())
                    .as("resolved stdout=%s stderr=%s", resolved.stdout(), resolved.stderr())
                    .isZero();
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "AGENTS.md")
                            .source())
                    .contains("resolved-guide");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/secret.md")
                            .expectedAbsence())
                    .isTrue();
            passed("single-file-cli-sync-merges-conflicts-and-keeps-unselected-baselines-and-local-deletions");
        }
    }

    private void publicProjection() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture"), path("exports"), auth, workspace);
        privateRead.set(false);
        try (var executor = adapter(path("socket"), 8, fixture.exports())) {
            var result = executor.execute(
                    principal,
                    workspace,
                    "public-native",
                    Optional.empty(),
                    "test $(git rev-list --count HEAD) = 1 && test ! -e private && "
                            + "test ! -e .poketto/publishing.yaml && cat article/index.md && "
                            + "! grep -R -F 'secret-needle' --exclude-dir=.git . && "
                            + "! git cat-file -e " + fixture.sourceCommit() + "^{commit} && poketto status && "
                            + "if poketto save article/index.md; then exit 98; fi",
                    Duration.ofSeconds(10),
                    new Cancellation());
            assertThat(result.exitCode())
                    .as("public projection stdout=%s stderr=%s", result.stdout(), result.stderr())
                    .isZero();
            assertThat(result.stdout()).contains("public-native-body").doesNotContain("secret-needle");
            assertThat(result.stdout())
                    .contains("\"scope\": \"public\"", "\"baseCommit\": \"" + result.commit() + "\"");
            assertThat(result.stdout()).contains("READ_ONLY_SCOPE");
            assertThat(result.commit()).isNotEqualTo(fixture.sourceCommit());
            passed("public-scope-real-projection-has-no-private-files-metadata-or-original-history");
            privateRead.set(true);
            var unchanged = executor.execute(
                    principal,
                    workspace,
                    "public-native",
                    Optional.empty(),
                    "test ! -e private && test $(git rev-list --count HEAD) = 1",
                    Duration.ofSeconds(5),
                    new Cancellation());
            assertThat(unchanged.exitCode()).isZero();
            assertThat(unchanged.commit()).isEqualTo(result.commit());
            passed("permission-increase-does-not-expand-existing-public-worker-files");
            fixture.withdraw();
            assertThatThrownBy(() -> executor.execute(
                            principal,
                            workspace,
                            "public-native",
                            Optional.empty(),
                            "printf output-after-withdrawal",
                            Duration.ofSeconds(5),
                            new Cancellation()))
                    .isInstanceOf(RuntimeException.class);
            passed("withdrawn-public-projection-denies-further-worker-output");
        } finally {
            privateRead.set(true);
        }
    }

    private void abandon() throws Exception {
        var executor = adapter(path("socket"));
        CompletableFuture.runAsync(() -> execute(executor, "abandoned", descendant(), new Cancellation()));
        control("await-descendant");
        System.out.println(JSON.writeValueAsString(Map.of("abandon", "READY")));
        System.out.flush();
        Runtime.getRuntime().halt(0);
    }

    private RepositoryExecutor.ExecutionResult execute(
            IsolatedRepositoryExecutor executor, String session, String command, Cancellation cancellation) {
        return executor.execute(
                principal, workspace, session, Optional.empty(), command, Duration.ofSeconds(25), cancellation);
    }

    private void close(IsolatedRepositoryExecutor executor, String session) {
        executor.closed(
                new McpSessionClosed(workspace, principal.subjectId(), session, McpSessionClosed.Reason.CLIENT_DELETE));
    }

    private static String descendant() {
        return "python3 -c \"import subprocess,time; subprocess.Popen(['sleep','60'],start_new_session=True); print('native-child-ready',flush=True); time.sleep(60)\"";
    }

    private void control(String operation) throws Exception {
        Path directory = path("control");
        String id = UUID.randomUUID().toString();
        Path temporary = directory.resolve("request.tmp");
        Files.writeString(temporary, JSON.writeValueAsString(Map.of("operation", operation, "id", id)));
        Files.move(temporary, directory.resolve("request.json"), StandardCopyOption.ATOMIC_MOVE);
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            Path response = directory.resolve("response.json");
            if (Files.exists(response)) {
                JsonNode result = JSON.readTree(Files.readString(response));
                if (result.path("id").asString("").equals(id)) {
                    Files.delete(response);
                    assertThat(result.path("ok").booleanValue())
                            .withFailMessage(result.toString())
                            .isTrue();
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Native controller did not acknowledge fixture operation");
    }

    private void passed(String name) {
        passed(name, "source", "synthetic-only");
    }

    private void passed(String name, String key, Object value) {
        tests++;
        System.out.println(JSON.writeValueAsString(Map.of("test", name, "result", "PASS", key, value)));
    }

    private Path path(String property) {
        return Path.of(config.path(property).stringValue());
    }

    private static double millis(long start) {
        return (System.nanoTime() - start) / 1_000_000.0;
    }

    private static String classHash(Class<?> type) throws Exception {
        try (var bytes = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return hash(bytes.readAllBytes());
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static AuthPrincipal principal() {
        var principal = mock(AuthPrincipal.class);
        when(principal.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(principal.subjectId()).thenReturn(UUID.randomUUID());
        when(principal.accountId()).thenReturn(UUID.randomUUID());
        return principal;
    }

    private static final class Cancellation implements ExecutionCancellation {
        private boolean cancelled;
        private final List<Runnable> callbacks = new ArrayList<>();

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Registration onCancel(Runnable callback) {
            synchronized (this) {
                if (!cancelled) {
                    callbacks.add(callback);
                    return () -> {
                        synchronized (this) {
                            callbacks.remove(callback);
                        }
                    };
                }
            }
            callback.run();
            return () -> {};
        }

        void cancel() {
            List<Runnable> pending;
            synchronized (this) {
                cancelled = true;
                pending = List.copyOf(callbacks);
                callbacks.clear();
            }
            pending.forEach(Runnable::run);
        }
    }
}
