package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class ExecutionTimingsTests {

    @Test
    void aCompletedCallReportsEveryStageAndItsBridgeWork(CapturedOutput output) throws Exception {
        var timings = new ExecutionTimings();
        for (ExecutionTimings.Stage stage : ExecutionTimings.Stage.values()) {
            if (stage == ExecutionTimings.Stage.COMMAND) {
                timings.bridged(3_000_000);
                timings.bridged(4_000_000);
                Thread.sleep(10);
            }
            timings.end(stage);
        }

        timings.record();

        String line = recordLine(output);
        assertThat(line).doesNotContain("=-1");
        assertThat(line).contains("stoppedIn=none", "bridgeMs=7", "bridgeRequests=2");
        assertThat(Long.parseLong(value(line, "commandMs"))).isGreaterThanOrEqualTo(10);
        assertThat(Long.parseLong(value(line, "totalMs")))
                .isGreaterThanOrEqualTo(Long.parseLong(value(line, "commandMs")));
    }

    @Test
    void aRefusedCallShowsWhereItStoppedAndHowLongItSpentThere(CapturedOutput output) throws Exception {
        var timings = new ExecutionTimings();
        timings.end(ExecutionTimings.Stage.AUTHORIZE);
        timings.end(ExecutionTimings.Stage.JOURNAL);
        Thread.sleep(10);

        timings.record();

        String line = recordLine(output);
        assertThat(line).contains("stoppedIn=permitMs");
        assertThat(Long.parseLong(value(line, "permitMs"))).isGreaterThanOrEqualTo(10);
        assertThat(line).contains("admitMs=-1", "commandMs=-1", "completeMs=-1", "bridgeRequests=0");
    }

    @Test
    void aCommandThatFailsAfterStartingReportsItsRunningTime(CapturedOutput output) throws Exception {
        var timings = new ExecutionTimings();
        for (ExecutionTimings.Stage stage : ExecutionTimings.Stage.values()) {
            if (stage == ExecutionTimings.Stage.COMMAND) {
                break;
            }
            timings.end(stage);
        }
        Thread.sleep(10);

        timings.record();

        String line = recordLine(output);
        assertThat(line).contains("stoppedIn=commandMs", "completeMs=-1");
        assertThat(Long.parseLong(value(line, "commandMs"))).isGreaterThanOrEqualTo(10);
    }

    @Test
    void containmentAfterAFailureIsReportedApartFromTheStageThatFailed(CapturedOutput output) throws Exception {
        var timings = new ExecutionTimings();
        for (ExecutionTimings.Stage stage : ExecutionTimings.Stage.values()) {
            if (stage == ExecutionTimings.Stage.COMMAND) {
                break;
            }
            timings.end(stage);
        }
        Thread.sleep(10);
        timings.failed();
        // Stopping the sandbox and releasing the copy happen after the failure is caught.
        Thread.sleep(60);
        timings.failed();

        timings.record();

        String line = recordLine(output);
        assertThat(line).contains("stoppedIn=commandMs");
        assertThat(Long.parseLong(value(line, "commandMs"))).isBetween(10L, 59L);
        assertThat(Long.parseLong(value(line, "cleanupMs"))).isGreaterThanOrEqualTo(60);
    }

    private static String recordLine(CapturedOutput output) {
        return output.getOut()
                .lines()
                .filter(line -> line.contains("repo_exec stages"))
                .reduce((first, second) -> second)
                .orElseThrow();
    }

    private static String value(String line, String key) {
        var matcher = Pattern.compile(key + "=(-?\\d+)").matcher(line);
        assertThat(matcher.find()).as(key).isTrue();
        return matcher.group(1);
    }
}
