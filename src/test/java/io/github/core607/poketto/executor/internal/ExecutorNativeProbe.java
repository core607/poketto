package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthRevocation;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.ExecutionCancellation;
import io.github.core607.poketto.mcp.McpSessionClosed;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
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
        doAnswer(call -> ((java.util.function.Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
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
        else if (args[1].equals("exports")) probe.portableExports();
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
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        mock(io.github.core607.poketto.assets.MediaFileService.class),
                        org.mockito.Mockito.mock(io.github.core607.poketto.content.AuthorizedRepositoryReader.class),
                        org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryPatchService.class),
                        org.mockito.Mockito.mock(io.github.core607.poketto.content.RepositoryMoveService.class),
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
        moves();
        lostLocalMoveReply();
        uncertainMoveRecovery();
        portableExports();
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
            artifacts(executor);
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

    private void artifacts(IsolatedRepositoryExecutor executor) throws Exception {
        var created = execute(
                executor,
                "first",
                "set -eu; printf original-artifact > result.bin; "
                        + "poketto artifact create result.bin --type application/octet-stream; "
                        + "printf changed > result.bin; test ! -r \"$POKETTO_BRIDGE/../artifacts\"",
                new Cancellation());
        assertThat(created.exitCode())
                .as("%s %s", created.stdout(), created.stderr())
                .isZero();
        JsonNode descriptor = JSON.readTree(created.stdout());
        assertThat(descriptor.path("ok").booleanValue()).isTrue();
        String id = descriptor.path("artifact").path("artifactId").stringValue();
        assertThat(readArtifact(executor, "first", id))
                .isEqualTo("original-artifact".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(executor.readArtifact(principal, workspace, "other-client", id, 0, 64, new Cancellation()))
                .isEmpty();
        assertThat(executor.readArtifact(principal, WorkspaceId.random(), "first", id, 0, 64, new Cancellation()))
                .isEmpty();
        assertThat(executor.readArtifact(principal(), workspace, "first", id, 0, 64, new Cancellation()))
                .isEmpty();
        assertThat(execute(executor, "first", "poketto artifact remove " + id, new Cancellation())
                        .exitCode())
                .isZero();
        assertThat(executor.readArtifact(principal, workspace, "first", id, 0, 64, new Cancellation()))
                .isEmpty();
        passed("artifact-capture-is-immutable-protected-and-bound-to-workspace-key-and-client");

        var complete = execute(
                executor, "first", "python3 -c 'import sys; sys.stdout.write(\"x\"*65537)'", new Cancellation());
        assertThat(complete.exitCode()).isZero();
        assertThat(complete.stdout()).isEqualTo("x".repeat(16384));
        assertThat(complete.stdoutTruncated()).isTrue();
        assertThat(complete.artifactErrors()).isEmpty();
        Map<String, Object> full = complete.artifacts().get("stdout");
        assertThat(full.get("truncated")).isEqualTo(false);
        assertThat(readArtifact(executor, "first", (String) full.get("artifactId")))
                .isEqualTo("x".repeat(65537).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        passed("long-output-retains-complete-bytes-behind-bounded-preview");

        var limited = execute(
                executor,
                "first",
                "printf retained > after-output-limit; python3 -c 'import sys; sys.stdout.write(\"z\"*(5*1024*1024))'",
                new Cancellation());
        assertThat(limited.terminationReason()).isEqualTo(RepositoryExecutor.TerminationReason.OUTPUT_LIMIT);
        assertThat(limited.stdout()).isEqualTo("z".repeat(16384));
        assertThat(limited.artifactErrors()).isEmpty();
        Map<String, Object> prefix = limited.artifacts().get("stdout");
        assertThat(prefix.get("truncated")).isEqualTo(true);
        assertThat(readArtifact(executor, "first", (String) prefix.get("artifactId")))
                .isEqualTo("z".repeat(4 * 1024 * 1024).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(execute(executor, "first", "cat after-output-limit", new Cancellation())
                        .stdout())
                .isEqualTo("retained");
        passed("output-limit-kills-command-retains-bounded-artifact-and-unselected-work");
    }

    private byte[] readArtifact(IsolatedRepositoryExecutor executor, String session, String id) throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        RepositoryExecutor.ArtifactChunk chunk;
        do {
            chunk = executor.readArtifact(principal, workspace, session, id, output.size(), 65536, new Cancellation())
                    .orElseThrow();
            output.write(chunk.bytes());
        } while (output.size() < chunk.size());
        assertThat(hash(output.toByteArray())).isEqualTo(chunk.sha256());
        return output.toByteArray();
    }

    private void portableExports() throws Exception {
        try (var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("portable"), path("exports"), auth, workspace)) {
            byte[] publicBytes = "public-export-original".getBytes(StandardCharsets.UTF_8);
            byte[] privateBytes = "private-export-original".getBytes(StandardCharsets.UTF_8);
            fixture.seedMedia(auth, principal, publicBytes, privateBytes);
            var article = fixture.reader(auth).getFile(principal, workspace, Optional.empty(), "article.md");
            // The generic media fixture intentionally links a private dependency. This scenario
            // needs an exportable public article and a private frontmatter sentinel of its own.
            String before = fixture.patches(auth)
                    .apply(
                            principal,
                            workspace,
                            new io.github.core607.poketto.content.RepositoryPatch(
                                    article.commit(),
                                    List.of(new io.github.core607.poketto.content.RepositoryTextChange(
                                            "article.md",
                                            false,
                                            article.revision(),
                                            Optional.of(
                                                    "---\ntitle: Portable native article\nsecret: metadata-secret-needle\n---\n"
                                                            + "public-native-body\n[Manual](public/manual.pdf)\n")))))
                    .commit();
            for (boolean full : List.of(true, false)) {
                privateRead.set(full);
                String session = full ? "export-full" : "export-public";
                try (var executor = new ExecutorConfiguration()
                        .isolatedRepositoryExecutor(
                                auth,
                                fixture.exports(),
                                fixture.packages(auth),
                                fixture.media(auth),
                                fixture.reader(auth),
                                fixture.patches(auth),
                                fixture.moves(auth),
                                JSON,
                                path("socket"),
                                path("privateKey"),
                                8,
                                45,
                                8)) {
                    String command = "set -eu; printf 'unsaved-export-needle' > scratch.txt; "
                            + (full ? "printf '\\nunsaved-export-needle' >> article.md; " : "")
                            + "poketto export . --output bundle.zip";
                    var exported = executor.execute(
                            principal,
                            workspace,
                            session,
                            Optional.empty(),
                            command,
                            Duration.ofSeconds(25),
                            new Cancellation());
                    assertThat(exported.exitCode()).isZero();
                    JsonNode receipt = JSON.readTree(exported.stdout());
                    assertThat(receipt.path("result").path("scope").stringValue())
                            .isEqualTo(full ? "private" : "public");
                    var artifact = execute(
                            executor,
                            session,
                            "poketto artifact create bundle.zip --type application/zip",
                            new Cancellation());
                    String id = JSON.readTree(artifact.stdout())
                            .path("artifact")
                            .path("artifactId")
                            .stringValue();
                    byte[] zip = readArtifact(executor, session, id);
                    assertThat(hash(zip))
                            .isEqualTo(receipt.path("result").path("sha256").stringValue());
                    var files = new java.util.LinkedHashMap<String, byte[]>();
                    try (var input = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
                        for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry())
                            files.put(entry.getName(), input.readAllBytes());
                    }
                    assertThat(files.values())
                            .anySatisfy(bytes -> assertThat(bytes).containsExactly(publicBytes));
                    String text = files.entrySet().stream()
                            .filter(entry -> entry.getKey().endsWith(".md"))
                            .map(entry -> new String(entry.getValue(), StandardCharsets.UTF_8))
                            .collect(java.util.stream.Collectors.joining("\n"));
                    assertThat(text)
                            .doesNotContain(
                                    "unsaved-export-needle", "historic-secret-needle", "operator-secret-needle");
                    if (full) {
                        assertThat(text).contains("current-secret-needle", "metadata-secret-needle");
                        assertThat(files.values())
                                .anySatisfy(bytes -> assertThat(bytes).containsExactly(privateBytes));
                    } else {
                        assertThat(text)
                                .doesNotContain(
                                        "current-secret-needle", "metadata-secret-needle", "private-export-original");
                        assertThat(files.keySet()).noneMatch(name -> name.contains("private"));
                        var denied = execute(
                                executor,
                                session,
                                "poketto export private/secret.md --output denied.zip",
                                new Cancellation());
                        assertThat(denied.exitCode()).isEqualTo(1);
                        assertThat(denied.stdout()).contains("INVALID_EXPORT_SELECTION");
                    }
                    var conflict = execute(
                            executor,
                            session,
                            "printf 'keep-local-file' > occupied.zip; poketto export . --output occupied.zip",
                            new Cancellation());
                    assertThat(conflict.exitCode()).isEqualTo(1);
                    assertThat(conflict.stdout()).contains("LOCAL_FILE_CHANGED");
                    assertThat(execute(executor, session, "cat occupied.zip; cat scratch.txt", new Cancellation())
                                    .stdout())
                            .isEqualTo("keep-local-fileunsaved-export-needle");
                    assertThat(fixture.retainedPackages()).isZero();
                    assertThat(fixture.reader(auth)
                                    .getFile(principal, workspace, Optional.empty(), "article.md")
                                    .commit())
                            .contains(before);
                    close(executor, session);
                    assertThat(fixture.retainedPackages()).isZero();
                    passed(
                            full
                                    ? "private-cli-export-keeps-originals-and-unsaved-edits-without-changing-authority"
                                    : "public-cli-export-translates-only-host-owned-paths-and-preserves-existing-files");
                }
            }
        } finally {
            privateRead.set(true);
        }
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
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        fixture.moves(auth),
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
                    Duration.ofSeconds(30),
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
            var listed = execute(
                    executor, "media-import", "poketto media list --prefix private/ --limit 1", new Cancellation());
            assertThat(listed.exitCode())
                    .as("%s %s", listed.stdout(), listed.stderr())
                    .isZero();
            var page = JSON.readTree(listed.stdout()).path("result");
            assertThat(page.path("indexSource").stringValue()).isEqualTo("worktree");
            assertThat(page.path("items").get(0).path("path").stringValue()).isEqualTo("private/generated.pdf");
            assertThat(page.path("total").intValue()).isEqualTo(2);
            assertThat(page.path("nextOffset").intValue()).isEqualTo(1);
            String indexVersion = page.path("indexVersion").stringValue();
            var nextPage = execute(
                    executor,
                    "media-import",
                    "poketto media list --prefix private/ --offset 1 --limit 1 --index-version " + indexVersion,
                    new Cancellation());
            assertThat(nextPage.exitCode()).isZero();
            assertThat(JSON.readTree(nextPage.stdout())
                            .path("result")
                            .path("items")
                            .get(0)
                            .path("path")
                            .stringValue())
                    .isEqualTo("private/manual.pdf");
            var repeated = executor.execute(
                    principal,
                    workspace,
                    "media-import",
                    Optional.empty(),
                    "set -eu; python3 -c \"from pathlib import Path; p=Path('.poketto/assets.json'); p.write_bytes(b' \\n'+p.read_bytes()+b'\\n')\"; "
                            + "before=$(sha256sum .poketto/assets.json); "
                            + "poketto media import private/generated.bin --as private/generated.pdf --type application/pdf --key native_import_original_01; "
                            + "test \"$before\" = \"$(sha256sum .poketto/assets.json)\"; poketto save .poketto/assets.json private/with-media.md",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(repeated.exitCode())
                    .as("repeat stdout=%s stderr=%s", repeated.stdout(), repeated.stderr())
                    .isZero();
            assertThat(repeated.stdout()).contains(identity);
            var changedPage = execute(
                    executor,
                    "media-import",
                    "poketto media list --offset 1 --index-version " + indexVersion,
                    new Cancellation());
            assertThat(changedPage.exitCode()).isEqualTo(1);
            assertThat(changedPage.stdout()).contains("MEDIA_INDEX_CHANGED");
            var historicalPage = execute(
                    executor,
                    "media-import",
                    "poketto media list --prefix private/ --commit " + initial,
                    new Cancellation());
            assertThat(historicalPage.exitCode()).isZero();
            assertThat(historicalPage.stdout())
                    .contains("repository", "private/manual.pdf", initial)
                    .doesNotContain("private/generated.pdf");
            passed("media-list-sees-unsaved-imports-pages-current-index-and-retains-historical-catalogs");
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
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        fixture.moves(auth),
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
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        fixture.moves(auth),
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
            var publicList = execute(
                    executor,
                    "media-public",
                    "printf 'invalid-local-index' > .poketto/assets.json; poketto media list",
                    new Cancellation());
            assertThat(publicList.exitCode())
                    .as("%s %s", publicList.stdout(), publicList.stderr())
                    .isZero();
            var approvedPage = JSON.readTree(publicList.stdout()).path("result");
            assertThat(approvedPage.path("indexSource").stringValue()).isEqualTo("public-projection");
            assertThat(approvedPage.path("total").intValue()).isEqualTo(1);
            assertThat(approvedPage.path("items").get(0).propertyNames())
                    .containsExactlyInAnyOrder("path", "mediaType", "size");
            assertThat(publicList.stdout()).doesNotContain("private/manual.pdf", originalCommit, "assetId", "revision");
            var historicalDenied = execute(
                    executor, "media-public", "poketto media list --commit " + originalCommit, new Cancellation());
            assertThat(historicalDenied.exitCode()).isEqualTo(1);
            assertThat(historicalDenied.stdout()).contains("INVALID_MEDIA_REQUEST");
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
            fixture.withdraw();
            assertThatThrownBy(() -> execute(executor, "media-public", "poketto media list", new Cancellation()))
                    .isInstanceOf(RuntimeException.class);
            passed("public-media-list-ignores-local-index-tampering-and-stops-after-withdrawal");
        } finally {
            privateRead.set(true);
        }
    }

    private IsolatedRepositoryExecutor moveAdapter(
            io.github.core607.poketto.content.internal.PublicExecutionNativeFixture fixture) {
        return new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        fixture.media(auth),
                        fixture.reader(auth),
                        fixture.patches(auth),
                        fixture.moves(auth),
                        JSON,
                        path("socket"),
                        path("privateKey"),
                        8,
                        45,
                        8);
    }

    private void lostLocalMoveReply() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("local-move-reply"), path("exports"), auth, workspace);
        try (var executor = moveAdapter(fixture)) {
            // Drop one confirmed real worker reply at the adapter boundary, after installation.
            var field = IsolatedRepositoryExecutor.class.getDeclaredField("worker");
            field.setAccessible(true);
            var original = (WorkerClient) field.get(executor);
            var intercepted = spy(original);
            var dropped = new java.util.concurrent.atomic.AtomicBoolean();
            var refuseInstall = new java.util.concurrent.atomic.AtomicBoolean();
            doAnswer(call -> {
                        WorkerClient.PreparedRequest request = call.getArgument(0);
                        var payload = JSON.readTree(java.util.Base64.getUrlDecoder()
                                .decode(request.envelope().get("payload")));
                        if (payload.path("operation").asString("").equals("MOVE_COMMIT") && refuseInstall.get())
                            return JSON.valueToTree(Map.of("ok", false, "code", "MOVE_REJECTED"));
                        var response = original.send(request, call.getArgument(1));
                        if (payload.path("operation").asString("").equals("MOVE_COMMIT")
                                && dropped.compareAndSet(false, true)) {
                            assertThat(response.path("ok").asBoolean(false)).isTrue();
                            throw new WorkerUnavailableException();
                        }
                        return response;
                    })
                    .when(intercepted)
                    .send(any(), any());
            field.set(executor, intercepted);
            var moved = executor.execute(
                    principal,
                    workspace,
                    "lost-local-move",
                    Optional.empty(),
                    "poketto move private/secret.md private/moved.md",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(dropped).isTrue();
            assertThat(moved.exitCode()).isNotZero();
            assertThat(moved.stdout()).contains("LOCAL_MOVE_PENDING", "\"committed\": true");
            String commit = fixture.reader(auth)
                    .getFile(principal, workspace, Optional.empty(), "private/moved.md")
                    .commit()
                    .orElseThrow();
            var recovered = executor.execute(
                    principal,
                    workspace,
                    "lost-local-move",
                    Optional.empty(),
                    """
                    set -eu
                    test ! -e private/secret.md
                    printf 'later local edit' > private/moved.md
                    poketto recover
                    test "$(cat private/moved.md)" = 'later local edit'
                    """,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(recovered.exitCode())
                    .as("%s %s", recovered.stdout(), recovered.stderr())
                    .isZero();
            assertThat(recovered.stdout()).contains("\"worktreeUpdated\": true");
            assertThat(fixture.reader(auth)
                            .getFile(principal, workspace, Optional.empty(), "private/moved.md")
                            .commit()
                            .orElseThrow())
                    .isEqualTo(commit);
            assertThat(fixture.reader(auth)
                            .getFile(principal, workspace, Optional.empty(), "private/moved.md")
                            .source()
                            .orElseThrow())
                    .doesNotContain("later local edit");
            passed("lost-worker-move-reply-retains-session-and-recovers-without-overwriting-new-edits");
            // Model a local precondition refusal; the shared worker tests own the refusal itself.
            refuseInstall.set(true);
            var pending = executor.execute(
                    principal,
                    workspace,
                    "lost-local-move",
                    Optional.empty(),
                    """
                    set -eu
                    poketto save private/moved.md
                    poketto move private/moved.md private/skipped.md
                    """,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(pending.exitCode()).isNotZero();
            assertThat(pending.stdout()).contains("LOCAL_MOVE_CONFLICT");
            String remote = fixture.reader(auth)
                    .getFile(principal, workspace, Optional.empty(), "private/skipped.md")
                    .commit()
                    .orElseThrow();
            var skipped = executor.execute(
                    principal,
                    workspace,
                    "lost-local-move",
                    Optional.empty(),
                    """
                    set -eu
                    poketto recover --skip-local
                    test "$(cat private/moved.md)" = 'later local edit'
                    test ! -e private/skipped.md
                    if poketto save private/moved.md; then exit 99; fi
                    poketto sync private/moved.md
                    poketto sync private/skipped.md
                    test ! -e private/moved.md
                    test "$(cat private/skipped.md)" = 'later local edit'
                    """,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(skipped.exitCode())
                    .as("%s %s", skipped.stdout(), skipped.stderr())
                    .isZero();
            assertThat(skipped.stdout()).contains("\"localInstallationSkipped\": true", "REPOSITORY_CONFLICT");
            assertThat(fixture.reader(auth)
                            .getFile(principal, workspace, Optional.empty(), "private/skipped.md")
                            .commit())
                    .contains(remote);
            passed("skip-confirmed-local-move-keeps-files-and-baselines-and-allows-explicit-sync");
        }
    }

    private void moves() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("moves"), path("exports"), auth, workspace);
        try (var executor = moveAdapter(fixture)) {
            String setup = """
                    set -eu
                    mkdir -p private/box
                    printf 'first-original' > private/source.bin
                    poketto media import private/source.bin --as private/box/present.pdf --type application/pdf --key native_move_present_001
                    printf 'second-original' > private/source.bin
                    poketto media import private/source.bin --as private/box/absent.pdf --type application/pdf --key native_move_absent_001
                    rm private/source.bin
                    printf '[ref](../ref.md) ![media](present.pdf)' > private/box/note.md
                    printf '[note](box/note.md)' > private/ref.md
                    poketto save private/box/note.md private/ref.md .poketto/assets.json
                    printf 'unselected' > private/scratch.md
                    printf 'draft-original' > private/draft.bin
                    poketto media import private/draft.bin --as private/unsaved.pdf --type application/pdf --key native_move_unsaved_001
                    poketto media fetch private/box/present.pdf
                    """;
            var prepared = executor.execute(
                    principal,
                    workspace,
                    "native-moves",
                    Optional.empty(),
                    setup,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(prepared.exitCode())
                    .as("setup stdout=%s stderr=%s", prepared.stdout(), prepared.stderr())
                    .isZero();
            var moved = executor.execute(
                    principal,
                    workspace,
                    "native-moves",
                    Optional.empty(),
                    """
                    set -eu
                    poketto move private/box private/deeper/box
                    test ! -e private/box
                    test ! -e private/deeper/box/absent.pdf
                    test "$(cat private/deeper/box/present.pdf)" = first-original
                    test "$(cat private/scratch.md)" = unselected
                    python3 - <<'PY'
                    import json
                    from pathlib import Path
                    index=json.loads(Path('.poketto/assets.json').read_text())['files']
                    assert 'private/unsaved.pdf' in index
                    assert 'private/deeper/box/absent.pdf' in index
                    assert 'private/box/present.pdf' not in index
                    assert Path('private/ref.md').read_text() == '[note](deeper/box/note.md)'
                    assert Path('private/deeper/box/note.md').read_text() == '[ref](../../ref.md) ![media](present.pdf)'
                    PY
                    """,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(moved.exitCode())
                    .as("move stdout=%s stderr=%s", moved.stdout(), moved.stderr())
                    .isZero();
            assertThat(moved.stdout()).contains("\"worktreeUpdated\": true");
            var reader = fixture.reader(auth);
            var remoteIndex = reader.getFile(principal, workspace, Optional.empty(), RepositoryMediaIndex.PATH);
            assertThat(RepositoryMediaIndex.parse(
                                    remoteIndex.source().orElseThrow().getBytes(StandardCharsets.UTF_8))
                            .files())
                    .containsKeys("private/deeper/box/present.pdf", "private/deeper/box/absent.pdf")
                    .doesNotContainKeys("private/box/present.pdf", "private/unsaved.pdf");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), "private/ref.md")
                            .source())
                    .contains("[note](deeper/box/note.md)");
            var refused = executor.execute(
                    principal,
                    workspace,
                    "native-moves",
                    Optional.empty(),
                    """
                    set -eu
                    printf 'edited locally' > private/deeper/box/note.md
                    if poketto move private/deeper/box private/refused; then exit 99; fi
                    test "$(cat private/deeper/box/note.md)" = 'edited locally'
                    test ! -e private/refused
                    """,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(refused.exitCode()).isZero();
            assertThat(refused.stdout()).contains("LOCAL_MOVE_REJECTED");
            assertThat(reader.getFile(principal, workspace, Optional.empty(), RepositoryMediaIndex.PATH)
                            .commit())
                    .isEqualTo(remoteIndex.commit());
            passed("cli-move-repairs-references-preserves-unsaved-index-and-retains-optional-media");
            passed("dirty-move-preflight-preserves-local-edits-and-remote-authority");
        }
    }

    private void uncertainMoveRecovery() throws Exception {
        var fixture = new io.github.core607.poketto.content.internal.PublicExecutionNativeFixture(
                path("publicFixture").resolve("move-recovery"), path("exports"), auth, workspace, true);
        try (var executor = moveAdapter(fixture)) {
            var unknown = executor.execute(
                    principal,
                    workspace,
                    "recover-move",
                    Optional.empty(),
                    "printf 'unselected' > private/scratch.md; poketto move private/secret.md private/renamed.md",
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(unknown.exitCode()).isEqualTo(1);
            assertThat(unknown.stdout()).contains("WRITE_OUTCOME_UNKNOWN");
            fixture.restoreTransport();
            var recovered = executor.execute(
                    principal,
                    workspace,
                    "recover-move",
                    Optional.empty(),
                    """
                    set -eu
                    poketto status
                    poketto recover
                    test ! -e private/secret.md
                    test -f private/renamed.md
                    test "$(cat private/scratch.md)" = unselected
                    """,
                    Duration.ofSeconds(30),
                    new Cancellation());
            assertThat(recovered.exitCode())
                    .as("recovery stdout=%s stderr=%s", recovered.stdout(), recovered.stderr())
                    .isZero();
            assertThat(recovered.stdout()).contains("\"movePending\": true", "\"worktreeUpdated\": true");
            assertThat(fixture.pushes()).isEqualTo(1);
            passed("uncertain-cli-move-recovers-the-original-commit-and-installs-once");
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
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        fixture.moves(auth),
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
        var reader = fixture.reader(auth);
        try (var executor = new ExecutorConfiguration()
                .isolatedRepositoryExecutor(
                        auth,
                        fixture.exports(),
                        mock(io.github.core607.poketto.content.PortableContentExports.class),
                        fixture.media(auth),
                        reader,
                        fixture.patches(auth),
                        fixture.moves(auth),
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
            var publicArtifact = execute(
                    executor,
                    "public-native",
                    "poketto artifact create article/index.md --type text/plain",
                    new Cancellation());
            assertThat(publicArtifact.exitCode()).isZero();
            String publicArtifactId = JSON.readTree(publicArtifact.stdout())
                    .path("artifact")
                    .path("artifactId")
                    .stringValue();
            assertThat(new String(
                            readArtifact(executor, "public-native", publicArtifactId),
                            java.nio.charset.StandardCharsets.UTF_8))
                    .contains("public-native-body")
                    .doesNotContain("secret-needle");
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
            assertThatThrownBy(() -> executor.readArtifact(
                            principal, workspace, "public-native", publicArtifactId, 0, 64, new Cancellation()))
                    .isInstanceOf(RuntimeException.class);
            passed("public-artifact-delivery-rechecks-publication-before-returning-bytes");
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
