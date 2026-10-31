package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.github.core607.poketto.assets.MediaFileService;
import io.github.core607.poketto.auth.AuthService;
import io.github.core607.poketto.content.AuthorizedRepositoryReader;
import io.github.core607.poketto.content.RepositoryMoveService;
import io.github.core607.poketto.content.RepositoryPatchService;
import io.github.core607.poketto.content.RepositorySnapshotExports;
import io.github.core607.poketto.mcp.RepositoryExecutor;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

@EnabledOnOs(OS.LINUX)
class NativeRepositoryConfigurationTests {
    @TempDir
    Path root;

    @Test
    void enabledApplicationWiresOnlyNativeAndReleasesStorageOwnershipOnContextClose() throws Exception {
        root = root.toRealPath();
        // A second application context can acquire the same transfer root only after the first closes.
        for (int attempt = 0; attempt < 2; attempt++) {
            var meters = new SimpleMeterRegistry();
            try (var context = context(true)) {
                assertThat(context.getBeansOfType(RepositoryExecutor.class)).hasSize(1);
                assertThat(context.getBean(RepositoryExecutor.class)).isInstanceOf(NativeRepositoryExecutor.class);
                assertThat(context.getBeansOfType(IsolatedRepositoryExecutor.class))
                        .isEmpty();
                assertThat(context.getBean(AccountCopyMaintenance.class)).isNotNull();
                context.getBean("executorMetrics", MeterBinder.class).bindTo(meters);
                assertThat(meters.get("poketto.executor.sessions.active")
                                .gauge()
                                .value())
                        .isZero();
                assertThat(context.getBean(ExecutorHealthIndicator.class)
                                .health()
                                .getStatus()
                                .getCode())
                        .isEqualTo("DOWN");
            } finally {
                meters.close();
            }
        }
    }

    @Test
    void disabledExecutionNeedsNeitherNativeStorageNorLegacySigningKey() {
        try (var context = context(false)) {
            assertThat(context.getBeansOfType(RepositoryExecutor.class)).isEmpty();
            assertThat(context.getBeansOfType(AccountCopyMaintenance.class)).isEmpty();
            assertThat(context.getBean(ExecutorHealthIndicator.class)
                            .health()
                            .getStatus()
                            .getCode())
                    .isEqualTo("UP");
        }
    }

    private AnnotationConfigApplicationContext context(boolean enabled) {
        var context = new AnnotationConfigApplicationContext();
        if (enabled) {
            // Real XFS account storage has a separate native probe; this test owns application wiring.
            context.addBeanFactoryPostProcessor(
                    factory -> factory.registerSingleton("accountCopyStore", mock(AccountCopyStore.class)));
        }
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource(
                        "native-test",
                        Map.of(
                                "poketto.executor.enabled",
                                enabled,
                                "poketto.executor.socket",
                                root.resolve("missing.sock").toString(),
                                "poketto.executor.transfers.directory",
                                root.resolve("transfers").toString(),
                                "poketto.executor.copies.metadata-root",
                                root.resolve("accounts").toString(),
                                "poketto.executor.copies.bytes",
                                "8388608",
                                "poketto.executor.copies.inodes",
                                "256")));
        context.registerBean(AuthService.class, () -> mock(AuthService.class));
        context.registerBean(RepositorySnapshotExports.class, () -> mock(RepositorySnapshotExports.class));
        context.registerBean(AuthorizedRepositoryReader.class, () -> mock(AuthorizedRepositoryReader.class));
        context.registerBean(RepositoryPatchService.class, () -> mock(RepositoryPatchService.class));
        context.registerBean(RepositoryMoveService.class, () -> mock(RepositoryMoveService.class));
        context.registerBean(MediaFileService.class, () -> mock(MediaFileService.class));
        context.register(
                ExecutorConfiguration.class, ExecutorAccountConfiguration.class, ExecutorHealthIndicator.class);
        boolean refreshed = false;
        try {
            context.refresh();
            refreshed = true;
            return context;
        } finally {
            if (!refreshed) {
                context.close();
            }
        }
    }
}
