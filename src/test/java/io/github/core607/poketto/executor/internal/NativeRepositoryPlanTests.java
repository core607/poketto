package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeRepositoryPlanTests {
    private static final String BASE = "a".repeat(40);
    private static final String REMOTE = "b".repeat(40);
    private static final String PATH = "private/draft.md";
    private final AccountCopyRecord.Owner owner =
            new AccountCopyRecord.Owner(UUID.randomUUID(), UUID.randomUUID(), true);
    private final UUID copy = UUID.randomUUID();
    private final AccountCopyRecord.Writer writer = new AccountCopyRecord.Writer(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new AccountCopyRecord.NativeStorage(UUID.randomUUID(), "repository", 1048576, 128));
    private final long expiry = System.currentTimeMillis() + 60000;

    @Test
    void journalTransitionsKeepThePlanIdentityOriginalsAndAcceptedProgressUntilExplicitRelease() {
        var initial = new NativeRepositoryPlan(
                UUID.randomUUID(),
                NativeRepositoryPlan.Kind.SYNC,
                BASE,
                REMOTE,
                null,
                List.of(PATH),
                Map.of(),
                Set.of(),
                Map.of(),
                false);
        var file = new NativeRepositoryPlan.File(
                new NativePlanFile(1, "c".repeat(64)),
                new NativePlanFile(1, "d".repeat(64)),
                RetainedFileBaseline.saved(REMOTE, "remote"),
                false);
        NativeRepositoryPlan prepared = initial.withFile(PATH, file);
        assertThatThrownBy(() -> transition(prepared, initial))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("originals");
        NativeRepositoryPlan ready = prepared.staged(PATH).ready();
        NativeRepositoryPlan accepted = ready.accept(Map.of(PATH, "e".repeat(64)));
        assertThatThrownBy(() -> transition(accepted, ready))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acceptance");
        assertThatThrownBy(() -> transition(ready, prepared))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("incomplete");
        var replacement = new NativeRepositoryPlan(
                UUID.randomUUID(),
                ready.kind(),
                BASE,
                REMOTE,
                null,
                ready.paths(),
                ready.files(),
                ready.staged(),
                ready.accepted(),
                true);
        assertThatThrownBy(() -> transition(ready, replacement))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("explicit release");
        assertThatCode(() -> transition(accepted, null)).doesNotThrowAnyException();
    }

    @Test
    void oldSaveJournalsWithoutAPlanFieldRetainTheirOriginalState() {
        RetainedSaveState snapshot = new SelectedFileSaves.State(BASE).snapshot();
        String current = new String(SandcrabProtocol.write(snapshot), StandardCharsets.UTF_8);
        assertThat(current).contains(",\"nativePlan\":null");
        String old = current.replace(",\"nativePlan\":null", "");
        RetainedSaveState restored =
                SandcrabProtocol.read(old.getBytes(StandardCharsets.UTF_8), RetainedSaveState.class);
        assertThat(restored).isEqualTo(snapshot);
        assertThat(restored.nativePlan()).isNull();
    }

    private void transition(NativeRepositoryPlan current, NativeRepositoryPlan next) {
        AccountCopyTransitions.requireTransition(owner, record(current, 0), record(next, 1), expiry);
    }

    private AccountCopyRecord record(NativeRepositoryPlan plan, long revision) {
        var state = new SelectedFileSaves.State(BASE);
        state.nativePlan = plan;
        return new AccountCopyRecord(
                owner,
                copy,
                revision,
                expiry,
                writer,
                AccountCopyRecord.Phase.READY,
                null,
                null,
                state.snapshot(),
                null,
                new AccountCopyRecord.Original("f".repeat(64), 64, 0));
    }
}
