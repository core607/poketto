package io.github.core607.poketto.assets;

import static org.assertj.core.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ImageMemoryAdmissionTests {
    @Test
    void sharedWeightsRejectInspectionImmediatelyAndRecoverOnlyAfterResponseAndProducerEnd() {
        var budget = new ImageMemoryAdmission(ImageMemoryAdmission.MCP_BYTES, 2, Duration.ofSeconds(2));
        var scope = budget.acquire(ImageMemoryAdmission.MCP_BYTES).orElseThrow();
        var producer = scope.producer();
        scope.responseComplete();
        assertThat(budget.reservedBytes()).isEqualTo(ImageMemoryAdmission.MCP_BYTES);
        assertThat(budget.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES)).isEmpty();
        assertThat(budget.waitingRequests()).isZero();
        assertThatThrownBy(scope::producer).isInstanceOf(IllegalStateException.class);
        producer.close();
        producer.close();
        scope.responseComplete();
        assertThat(budget.reservedBytes()).isZero();
        var first = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        var second = budget.acquire(ImageMemoryAdmission.MCP_BYTES - ImageMemoryAdmission.BROWSER_BYTES)
                .orElseThrow();
        assertThat(budget.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES)).isEmpty();
        first.responseComplete();
        second.responseComplete();
        assertThat(budget.reservedBytes()).isZero();
        assertThat(budget.rejectedRequests()).isEqualTo(2);
    }

    @Test
    void producerEndDoesNotReleaseAResponseThatIsStillSending() {
        var budget = new ImageMemoryAdmission(ImageMemoryAdmission.BROWSER_BYTES, 1, Duration.ZERO);
        var scope = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        try (var producer = scope.producer()) {
            assertThat(budget.reservedBytes()).isEqualTo(ImageMemoryAdmission.BROWSER_BYTES);
        }
        assertThat(budget.tryAcquire(ImageMemoryAdmission.BROWSER_BYTES)).isEmpty();
        scope.responseComplete();
        assertThat(budget.reservedBytes()).isZero();
    }

    @Test
    void waiterCountIsBoundedAndInterruptDoesNotLeakAdmission() throws Exception {
        var budget = new ImageMemoryAdmission(ImageMemoryAdmission.BROWSER_BYTES, 1, Duration.ofSeconds(5));
        var scope = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        var started = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var waiting = pool.submit(() -> {
                started.countDown();
                return budget.acquire(ImageMemoryAdmission.BROWSER_BYTES);
            });
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (budget.waitingRequests() == 0 && System.nanoTime() < deadline) Thread.onSpinWait();
            assertThat(budget.waitingRequests()).isOne();
            assertThat(budget.acquire(ImageMemoryAdmission.BROWSER_BYTES)).isEmpty();
            waiting.cancel(true);
        } finally {
            scope.responseComplete();
        }
        assertThat(budget.waitingRequests()).isZero();
        assertThat(budget.reservedBytes()).isZero();
        assertThat(budget.rejectedRequests()).isEqualTo(2);
    }

    @Test
    void waitingHasAFiniteDeadlineAndSuccessfulWaiterReusesReleasedCapacity() throws Exception {
        var budget = new ImageMemoryAdmission(ImageMemoryAdmission.BROWSER_BYTES, 1, Duration.ofMillis(50));
        var held = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        assertThat(budget.acquire(ImageMemoryAdmission.BROWSER_BYTES)).isEmpty();
        assertThat(budget.waitingRequests()).isZero();
        held.responseComplete();
        var resumed = budget.acquire(ImageMemoryAdmission.BROWSER_BYTES).orElseThrow();
        resumed.responseComplete();
        assertThat(budget.reservedBytes()).isZero();
    }
}
