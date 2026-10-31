package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

@EnabledOnOs(OS.LINUX)
class NativeMigrationControllerLinuxTests {
    @Test
    void controllerFilesystemAndInheritedLockFaults() throws Exception {
        Path staged = Path.of("/runtime/migration-tests/native_migration_tests.py");
        Path script = Files.exists(staged)
                ? staged
                : Path.of("executor-service/native_migration_tests.py").toAbsolutePath();
        var builder = new ProcessBuilder("python3", "-I", script.toString()).redirectErrorStream(true);
        builder.environment()
                .put(
                        "MIGRATION_TEST_JAVA",
                        Path.of(System.getProperty("java.home"), "bin", "java").toString());
        String main = Path.of(NativeMigrationControl.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        String tests = Path.of(NativeMigrationLockProbe.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        builder.environment().put("MIGRATION_TEST_CLASSPATH", main + File.pathSeparator + tests);
        Process process = builder.start();
        boolean complete = process.waitFor(60, TimeUnit.SECONDS);
        if (!complete) {
            process.destroyForcibly().waitFor();
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(complete).as(output).isTrue();
        assertThat(process.exitValue()).as(output).isZero();
        assertThat(output).contains("Ran 9 tests");
    }
}
