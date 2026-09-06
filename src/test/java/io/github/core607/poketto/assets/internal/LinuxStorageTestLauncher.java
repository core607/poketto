package io.github.core607.poketto.assets.internal;

import java.io.PrintWriter;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;

/** Runs the owning JUnit class without Gradle or a Docker daemon inside the test container. */
public final class LinuxStorageTestLauncher {
    private static final java.util.List<String> SUITES = java.util.List.of(
            "io.github.core607.poketto.assets.internal.LocalManagedBlobStoreTests",
            "io.github.core607.poketto.assets.internal.ManagedAssetDeliveryTests");

    private LinuxStorageTestLauncher() {}

    public static void main(String[] args) {
        if (!System.getProperty("os.name").equals("Linux")) {
            throw new IllegalStateException("durable storage replay requires a real Linux runtime");
        }
        var summaryListener = new SummaryGeneratingListener();
        var request = LauncherDiscoveryRequestBuilder.request()
                .selectors(SUITES.stream().map(DiscoverySelectors::selectClass).toList())
                .build();
        try (var launcher = LauncherFactory.openSession()) {
            launcher.getLauncher().registerTestExecutionListeners(summaryListener);
            launcher.getLauncher().execute(request);
        }
        var summary = summaryListener.getSummary();
        summary.printTo(new PrintWriter(System.out, true));
        summary.printFailuresTo(new PrintWriter(System.err, true));
        System.out.printf(
                "linuxStorageTest class=%s found=%d started=%d succeeded=%d skipped=%d aborted=%d failed=%d%n",
                SUITES,
                summary.getTestsFoundCount(),
                summary.getTestsStartedCount(),
                summary.getTestsSucceededCount(),
                summary.getTestsSkippedCount(),
                summary.getTestsAbortedCount(),
                summary.getTestsFailedCount());
        if (summary.getTestsFoundCount() == 0
                || summary.getTestsSkippedCount() != 0
                || summary.getTestsAbortedCount() != 0
                || summary.getTotalFailureCount() != 0
                || summary.getTestsSucceededCount() != summary.getTestsFoundCount()) {
            throw new IllegalStateException(
                    "durable storage tests must execute with no missing, skipped or failed tests");
        }
    }
}
