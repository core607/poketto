package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.core607.poketto.auth.Capability;
import io.github.core607.poketto.content.ContentExportException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class NativeExportCommandsTests extends NativeExportFixture {
    @Test
    void theWholeCommandDeadlineRetiresThePackageDuringItsChecksumPass() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        var copying = new AtomicBoolean();
        var checks = new AtomicInteger();
        var retired = new CountDownLatch(1);
        when(lease.remaining()).thenAnswer(call -> {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new WorkerUnavailableException(new IllegalStateException("Fixture command expired"));
            }
            return Duration.ofNanos(left);
        });
        doAnswer(call -> {
                    copying.set(true);
                    return call.callRealMethod();
                })
                .when(packages)
                .copyTo(any(), any(), any(), any(), any());
        doAnswer(call -> {
                    // The third authorization is inside the checksum pass, before the first output write.
                    if (copying.get() && checks.incrementAndGet() == 3) {
                        assertThat(retired.await(4, TimeUnit.SECONDS)).isTrue();
                    }
                    return authorized();
                })
                .when(auth)
                .authorize(any(), any(), any(Capability[].class));
        doAnswer(call -> {
                    call.callRealMethod();
                    retired.countDown();
                    return null;
                })
                .when(packages)
                .closeClient(any(), any(), anyString());
        assertThat(command(List.of("private/secret.md"), "expired.zip", false).code())
                .isEqualTo("EXPORT_NOT_FOUND");
        assertThat(checks.get()).isEqualTo(3);
        assertThat(uploads.get()).isZero();
        assertThat(worktree).doesNotContainKey("expired.zip");
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void aCompletedPackageKeepsTheRemainingCommandTimeForTransfer() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        when(lease.remaining()).thenAnswer(call -> {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                throw new WorkerUnavailableException(new IllegalStateException("Fixture command expired"));
            }
            return Duration.ofNanos(left);
        });
        doAnswer(call -> {
                    // Build finishes promptly. Transfer starts beyond its reserved build budget, but inside the command
                    // budget.
                    Thread.sleep(Duration.ofMillis(8250));
                    assertThat(lease.remaining()).isGreaterThan(Duration.ZERO);
                    return call.callRealMethod();
                })
                .when(packages)
                .copyTo(any(), any(), any(), any(), any());
        BridgeReplies.Reply reply = command(List.of("private/secret.md"), "slow-copy.zip", false);
        assertThat(reply.ok()).isTrue();
        assertThat(String.join("\n", zip("slow-copy.zip").values())).contains("current-secret-needle");
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void actualZipUsesRemoteAuthorityAndTheOpaqueReplyContainsOnlyItsLocalReceipt() throws Exception {
        worktree.put("private/secret.md", "unsaved-local-only".getBytes(StandardCharsets.UTF_8));
        BridgeReplies.Reply reply = command(List.of("private/secret.md"), "export.zip", false);
        assertThat(reply.ok()).isTrue();
        assertThat(reply.result()).isInstanceOfSatisfying(BridgeReplies.ExportResult.class, result -> {
            assertThat(result.scope()).isEqualTo("private");
            assertThat(result.saved()).isFalse();
            assertThat(result.bytes()).isEqualTo(worktree.get("export.zip").length);
        });
        assertThat(String.join("\n", zip("export.zip").values()))
                .contains("current-secret-needle")
                .doesNotContain("unsaved-local-only", "historic-secret-needle");
        assertThat(SandcrabProtocol.write(reply)).hasSizeLessThan(65536);
        assertThat(new String(SandcrabProtocol.write(reply), StandardCharsets.UTF_8))
                .doesNotContain("handle", "expiresAt");
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void publicCopyUsesOnlyItsHostProjectionAndForcesSanitizedPublicScope() throws Exception {
        scope(false);
        String visible = record.publicExport().sourcePaths().entrySet().stream()
                .filter(entry -> entry.getValue().equals("public/article.md"))
                .findFirst()
                .orElseThrow()
                .getKey();
        assertThat(command(List.of(visible), "public.zip", false).ok()).isTrue();
        String content = String.join("\n", zip("public.zip").values());
        assertThat(content)
                .contains("public-native-body")
                .doesNotContain(
                        "metadata-secret-needle",
                        "comment-secret-needle",
                        "current-secret-needle",
                        "public/article.md",
                        "operator-secret-needle");
        assertThat(command(List.of("public/article.md"), "forged.zip", true).code())
                .isEqualTo("INVALID_EXPORT_SELECTION");
        assertThat(worktree).doesNotContainKey("forged.zip");
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void identicalTargetsAreAcceptedAndDifferentUserBytesRemainUntouched() throws Exception {
        assertThat(command(List.of("private/secret.md"), "export.zip", false).ok())
                .isTrue();
        byte[] original = worktree.get("export.zip");
        assertThat(command(List.of("private/secret.md"), "export.zip", false).ok())
                .isTrue();
        assertThat(worktree.get("export.zip")).isEqualTo(original);
        byte[] user = "user-owned-file".getBytes(StandardCharsets.UTF_8);
        worktree.put("export.zip", user);
        assertThat(command(List.of("private/secret.md"), "export.zip", false).code())
                .isEqualTo("LOCAL_FILE_CHANGED");
        assertThat(worktree.get("export.zip")).isEqualTo(user);
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void currentReadRevocationStopsRealPackageTransferAndReleasesItsHandle() throws Exception {
        doAnswer(call -> {
                    denied.set(true);
                    return call.callRealMethod();
                })
                .when(packages)
                .copyTo(any(), any(), any(), any(), any());
        assertThat(command(List.of("private/secret.md"), "denied.zip", false).code())
                .isEqualTo("ACCESS_DENIED");
        assertThat(uploads.get()).isZero();
        assertThat(worktree).doesNotContainKey("denied.zip");
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void withdrawalInvalidatesTheActualPublicPackageBeforeLocalInstallation() throws Exception {
        scope(false);
        String visible = record.publicExport().sourcePaths().entrySet().stream()
                .filter(entry -> entry.getValue().equals("public/article.md"))
                .findFirst()
                .orElseThrow()
                .getKey();
        doAnswer(call -> {
                    fixture.withdraw();
                    return call.callRealMethod();
                })
                .when(packages)
                .copyTo(any(), any(), any(), any(), any());
        assertThat(command(List.of(visible), "withdrawn.zip", true).code()).isEqualTo("EXPORT_UNAVAILABLE");
        assertThat(uploads.get()).isZero();
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void profileTransferBoundRefusesBeforeNativeStagingOrUpload() throws Exception {
        when(lease.transferLimit()).thenReturn(1L);
        assertThat(command(List.of("private/secret.md"), "large.zip", false).code())
                .isEqualTo("EXPORT_CAPACITY");
        assertThat(uploads.get()).isZero();
        assertThat(fixture.retainedPackages()).isZero();
    }

    @Test
    void lostNativeOutcomeIsNeverResentOrMaskedByCleanupFailure() throws Exception {
        doAnswer(call -> {
                    install(call.getArgument(0), call.getArgument(1));
                    throw new WorkerUnavailableException();
                })
                .when(lease)
                .upload(anyString(), any());
        doAnswer(call -> {
                    call.callRealMethod();
                    throw new ContentExportException(ContentExportException.Reason.UNAVAILABLE);
                })
                .when(packages)
                .closeClient(any(), any(), anyString());
        assertThatThrownBy(() -> command(List.of("private/secret.md"), "uncertain.zip", false))
                .isInstanceOf(WorkerUnavailableException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
        assertThat(uploads.get()).isEqualTo(1);
        verify(packages, times(1)).create(any(), any(), any(), anyBoolean(), any());
        assertThat(fixture.retainedPackages()).isZero();
    }
}
