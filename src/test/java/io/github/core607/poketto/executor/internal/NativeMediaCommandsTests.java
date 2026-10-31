package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.assets.ManagedAssetReference;
import io.github.core607.poketto.auth.AuthException;
import io.github.core607.poketto.auth.AuthPrincipal;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.auth.MembershipRole;
import io.github.core607.poketto.auth.WorkspaceAccess;
import io.github.core607.poketto.content.RepositoryMediaIndex;
import io.github.core607.poketto.content.internal.PublicExecutionNativeFixture;
import io.github.core607.poketto.workspace.WorkspaceId;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledOnOs(OS.LINUX)
class NativeMediaCommandsTests {
    @TempDir
    Path root;

    private final AuthService auth = mock(AuthService.class);
    private final AuthPrincipal actor = mock(AuthPrincipal.class);
    private final AccountCommand held = mock(AccountCommand.class);
    private final NativeRepositoryLease lease = mock(NativeRepositoryLease.class);
    private final WorkspaceId workspace = WorkspaceId.random();
    private final Map<String, byte[]> worktree = new HashMap<>();
    private final List<RetainedSaveState> journal = new ArrayList<>();
    private final AtomicInteger installs = new AtomicInteger();
    private PublicExecutionNativeFixture fixture;
    private NativeTransferStore storage;
    private NativeArtifacts artifacts;
    private NativeRepositoryFiles files;
    private NativeRepositoryHost host;
    private SelectedFileSaves.State state;
    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void configure() throws Exception {
        root = root.toRealPath();
        when(actor.kind()).thenReturn(AuthPrincipal.Kind.API_KEY);
        when(actor.subjectId()).thenReturn(UUID.randomUUID());
        when(actor.accountId()).thenReturn(UUID.randomUUID());
        when(auth.authorize(any(), any(), any(Capability[].class)))
                .thenAnswer(call -> new WorkspaceAccess(
                        call.getArgument(1),
                        call.getArgument(0),
                        MembershipRole.OWNER,
                        EnumSet.allOf(Capability.class)));
        doAnswer(call -> ((Supplier<?>) call.getArgument(3)).get())
                .when(auth)
                .withAuthorization(any(), any(), anySet(), any());
        fixture = new PublicExecutionNativeFixture(root, root.resolve("exports"), auth, workspace);
        storage = new NativeTransferStore(root.resolve("transfers"), 512L * 1024 * 1024, 64);
        artifacts = new NativeArtifacts(storage, Clock.systemUTC());
        files = new NativeRepositoryFiles(storage, (session, path, maximum, target, authorize) -> {
            byte[] bytes = worktree.get(path.substring("work/repository/".length()));
            if (bytes == null) {
                throw new SandcrabClient.Rejected(404, "FILE_NOT_FOUND");
            }
            if (bytes.length > maximum) {
                throw new SandcrabClient.Rejected(400, "FILE_LIMIT");
            }
            try {
                Files.write(target, bytes, StandardOpenOption.CREATE_NEW);
            } catch (java.io.IOException failure) {
                throw new WorkerUnavailableException(failure);
            }
        });
        var owner = new AccountCopyRecord.Owner(actor.accountId(), workspace.value(), true);
        when(held.owner()).thenReturn(owner);
        state = new SelectedFileSaves.State(fixture.sourceCommit(), journal::add);
        when(held.state()).thenReturn(state);
        var writer = new AccountCopyRecord.Writer(
                actor.subjectId(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        UUID copy = UUID.randomUUID();
        UUID execution = UUID.randomUUID();
        when(held.record())
                .thenAnswer(call -> new AccountCopyRecord(
                        owner,
                        copy,
                        1,
                        System.currentTimeMillis() + 604_800_000,
                        writer,
                        AccountCopyRecord.Phase.RUNNING,
                        execution,
                        null,
                        state.snapshot(),
                        null,
                        new AccountCopyRecord.Original("a".repeat(64), 64, 0)));
        var saves = new SelectedFileSaves(auth, fixture.reader(auth), fixture.patches(auth), fixture.moves(auth));
        host = new NativeRepositoryHost(
                saves,
                files,
                new NativeArtifactCommands(files, artifacts),
                new NativeMediaCommands(files, fixture.media(auth), saves, auth),
                new NativePlanCommands(files, saves),
                mock(NativeExportCommands.class));
        when(lease.upload(anyString(), any())).thenAnswer(call -> install(call.getArgument(0), call.getArgument(1)));
    }

    private SandcrabProtocol.Upload install(String nativePath, NativeTransferStore.File file) throws Exception {
        installs.incrementAndGet();
        String path = nativePath.substring("work/repository/".length());
        boolean exists = worktree.containsKey(path);
        if (!exists) {
            worktree.put(path, Files.readAllBytes(file.path()));
        }
        return new SandcrabProtocol.Upload(
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString(),
                "repository",
                null,
                nativePath,
                "raw",
                file.size(),
                file.sha256(),
                "f".repeat(64),
                exists ? "failed" : "succeeded",
                exists ? "PATH_EXISTS" : null,
                exists ? "none" : "committed");
    }

    @AfterEach
    void close() {
        if (artifacts != null) {
            artifacts.close();
        }
        if (fixture != null) {
            fixture.close();
        }
    }

    private JsonNode request(String operation, String arguments) {
        return json.readTree(host.reply(
                actor,
                held,
                lease,
                SandcrabProtocol.write(new NativeRepositoryHost.Request(operation, json.readTree(arguments)))));
    }

    private JsonNode importBinary() {
        return request("media_import", """
                {"file":"scratch.bin","path":"private/new.bin","mediaType":"application/octet-stream",
                 "key":"native-media-import-0001","replace":false}
                """);
    }

    @Test
    void binaryImportStoresAnOriginalAndExplicitProposalWithoutTouchingUnsavedIndexOrGit() {
        byte[] binary = new byte[] {0, 1, 2, 3, -1, -2};
        worktree.put("scratch.bin", binary);
        byte[] originalIndex = RepositoryMediaIndex.empty().encode();
        worktree.put(RepositoryMediaIndex.PATH, originalIndex);
        JsonNode reply = importBinary();
        assertThat(reply.path("ok").booleanValue()).as(reply.toPrettyString()).isTrue();
        assertThat(reply.path("message").stringValue()).contains("local media index is unchanged", "Explicitly merge");
        JsonNode result = reply.path("result");
        assertThat(result.path("indexUpdated").booleanValue()).isFalse();
        assertThat(result.path("originalStored").booleanValue()).isTrue();
        assertThat(result.path("indexEntry").path("revision").stringValue()).isEqualTo(SessionWorker.hash(binary));
        var reference = new ManagedAssetReference(
                UUID.fromString(result.path("assetId").stringValue()),
                result.path("sha256").stringValue());
        assertThat(fixture.media(auth)
                        .describeOriginal(actor, workspace, reference)
                        .size())
                .isEqualTo(binary.length);
        assertThat(state.lastImport).isInstanceOf(BridgeReplies.ImportReceipt.class);
        assertThat(journal.getLast().lastImport().value().path("indexUpdated").booleanValue())
                .isFalse();
        assertThat(worktree.get(RepositoryMediaIndex.PATH)).isEqualTo(originalIndex);
        assertThat(worktree.get("scratch.bin")).isEqualTo(binary);
        assertThat(installs.get()).isZero();
        assertThat(fixture.pushes()).isZero();
    }

    @Test
    void fetchUsesTheExplicitlyEditedIndexAndNeverOverwritesDifferentLocalBytes() {
        byte[] binary = new byte[2 * 1024 * 1024 + 7];
        for (int index = 0; index < binary.length; index++) {
            binary[index] = (byte) index;
        }
        worktree.put("scratch.bin", binary);
        JsonNode imported = importBinary().path("result");
        var entry = json.treeToValue(imported.path("indexEntry"), RepositoryMediaIndex.Media.class);
        worktree.put(RepositoryMediaIndex.PATH, new RepositoryMediaIndex(Map.of("private/new.bin", entry)).encode());
        String request = """
                {"path":"private/new.bin","commit":null,"output":"download.bin"}
                """;
        assertThat(request("media_fetch", request).path("ok").booleanValue()).isTrue();
        assertThat(worktree.get("download.bin")).isEqualTo(binary);
        assertThat(request("media_fetch", request).path("ok").booleanValue()).isTrue();
        byte[] unsaved = "unrelated local edit".getBytes(StandardCharsets.UTF_8);
        worktree.put("download.bin", unsaved);
        assertThat(request("media_fetch", request).path("code").stringValue()).isEqualTo("LOCAL_FILE_EXISTS");
        assertThat(worktree.get("download.bin")).isEqualTo(unsaved);
    }

    @Test
    void createdBinaryArtifactIsImmutableAfterTheSandboxSourceChanges() {
        byte[] binary = new byte[] {0, 1, 2, -1};
        worktree.put("scratch.bin", binary);
        JsonNode created = request("artifact_create", """
                {"path":"scratch.bin","mediaType":"application/octet-stream"}
                """);
        assertThat(created.path("ok").booleanValue()).isTrue();
        String id = created.path("artifact").path("artifactId").stringValue();
        worktree.put("scratch.bin", new byte[] {9, 9, 9});
        assertThat(artifacts
                        .read(held.record(), id, 0, 65536, () -> {})
                        .orElseThrow()
                        .bytes())
                .isEqualTo(binary);
        assertThat(request("artifact_remove", "{\"artifactId\":\"" + id + "\"}")
                        .path("ok")
                        .booleanValue())
                .isTrue();
        assertThat(artifacts.read(held.record(), id, 0, 65536, () -> {})).isEmpty();
    }

    @Test
    void revokedWriteAuthorityCannotUploadAnOriginal() {
        worktree.put("scratch.bin", new byte[] {1, 2, 3});
        when(auth.authorize(actor, workspace, Capability.WRITE_PRIVATE))
                .thenThrow(new AuthException(AuthException.Code.DENIED));
        assertThat(importBinary().path("code").stringValue()).isEqualTo("ACCESS_DENIED");
        assertThat(journal).isEmpty();
        assertThat(installs.get()).isZero();
    }

    @Test
    void linkProposesAnExistingOriginalAndAListingUsesOnlyTheExplicitLocalIndex() {
        worktree.put("scratch.bin", new byte[] {1, 2, 3, 4});
        JsonNode imported = importBinary().path("result");
        JsonNode linked = request(
                "media_link",
                "{\"path\":\"private/linked.bin\",\"assetId\":\""
                        + imported.path("assetId").stringValue() + "\",\"revision\":\""
                        + imported.path("sha256").stringValue() + "\",\"replace\":false}");
        assertThat(linked.path("ok").booleanValue()).isTrue();
        assertThat(linked.path("result").path("indexUpdated").booleanValue()).isFalse();
        assertThat(linked.path("message").stringValue()).contains("local media index is unchanged");
        assertThat(worktree).doesNotContainKey(RepositoryMediaIndex.PATH);
        var entry = json.treeToValue(linked.path("result").path("indexEntry"), RepositoryMediaIndex.Media.class);
        worktree.put(RepositoryMediaIndex.PATH, new RepositoryMediaIndex(Map.of("private/linked.bin", entry)).encode());
        JsonNode listed = request("media_list", """
                {"prefix":"private/","offset":0,"limit":100,"indexVersion":null,"commit":null}
                """);
        assertThat(listed.path("result").path("total").intValue()).isEqualTo(1);
        assertThat(listed.path("result").path("items").get(0).path("path").stringValue())
                .isEqualTo("private/linked.bin");
        assertThat(installs.get()).isZero();
        assertThat(fixture.pushes()).isZero();
    }

    @Test
    void repeatingAnImportUsesTheSameDurableOriginalAndNeverEditsTheIndex() {
        worktree.put("scratch.bin", new byte[] {0, 2, 4, 6});
        JsonNode first = importBinary().path("result");
        JsonNode repeated = importBinary().path("result");
        assertThat(repeated.path("assetId")).isEqualTo(first.path("assetId"));
        assertThat(repeated.path("sha256")).isEqualTo(first.path("sha256"));
        assertThat(worktree).doesNotContainKey(RepositoryMediaIndex.PATH);
        assertThat(installs.get()).isZero();
    }
}
