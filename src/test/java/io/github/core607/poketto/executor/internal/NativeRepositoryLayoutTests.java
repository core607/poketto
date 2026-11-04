package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NativeRepositoryLayoutTests extends NativeRepositoryTestFixture {
    @Test
    void repositoryPathsStayInsideTheWorkRoot() {
        assertThat(NativeRepositoryLayout.repository("private/a b.md")).isEqualTo("work/repository/private/a b.md");
        assertThatThrownBy(() -> NativeRepositoryLayout.repository("../home/settings"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void freshAndReattachedShellsUseTheSamePersistentHomeAndRepository() {
        try (var executor = executor((run, timeout, handler) -> {})) {
            String attach = "/bin/rm -f -- /workspace/.poketto-git-baseline.bundle"
                    + " && export HOME=/workspace/home && cd /workspace/work/repository";
            String copy = execute(executor, "new").copyId();
            assertThat(commands).contains(attach);
            assertThat(commands).noneMatch(command -> command.contains("git clone"));
            sessions.values().forEach(session -> client.close(session.reference()));
            commands.clear();
            execute(executor, copy);
            assertThat(commands).contains(attach);
        }
    }

    @Test
    void eachCommandPublishesItsOwnDeadlineBeforeDispatch() {
        AccountCopyRecord before = legacyCopy();
        var storage = new AccountCopyRecord.NativeStorage(before.copyId(), "repository", 8_388_608, 256);
        var writer = new AccountCopyRecord.Writer(
                principal.subjectId(), UUID.fromString(epoch), UUID.randomUUID(), UUID.randomUUID(), storage);
        var lease = new NativeRepositoryLease(client, storage, writer, 120_000, 65536, 8_388_608, 30_000, () -> {});
        lease.open();
        assertThatThrownBy(() -> lease.outOfTime(Duration.ZERO)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(lease::remaining).isInstanceOf(IllegalStateException.class);
        doAnswer(call -> {
                    assertThat(lease.outOfTime(Duration.ofSeconds(2))).isTrue();
                    assertThat(lease.outOfTime(Duration.ZERO)).isFalse();
                    assertThat(lease.remaining()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(1));
                    return null;
                })
                .when(client)
                .command(any(), any());
        lease.command(UUID.randomUUID(), "short", Duration.ofSeconds(1));
        doAnswer(call -> {
                    assertThat(lease.outOfTime(Duration.ofSeconds(2))).isFalse();
                    assertThat(lease.remaining()).isGreaterThan(Duration.ofSeconds(2));
                    return null;
                })
                .when(client)
                .command(any(), any());
        lease.command(UUID.randomUUID(), "longer", Duration.ofSeconds(30));
        assertThatThrownBy(() -> lease.outOfTime(Duration.ofSeconds(-1))).isInstanceOf(IllegalArgumentException.class);
    }
}
