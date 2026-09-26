package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Runs the application's fixed inspection with real Git and Bash in a copy materialized the way the
 * worker's launcher does it: a clone of the projection bundle without a remote, detached at the
 * projection commit. It needs Linux tools, so it runs where CI runs the unit tests.
 */
@EnabledOnOs(OS.LINUX)
class PublicCopyInspectionTests {
    @TempDir
    Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"true", "git status", "mkdir empty", "git branch kept"})
    void aMaterializedProjectionWithoutLocalWorkIsClean(String command) throws Exception {
        assertThat(inspectAfter(command)).isEqualTo("POKETTO_PUBLIC_COPY_CLEAN\n");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "printf x >> article/index.md",
                "rm AGENTS.md",
                "chmod +x AGENTS.md",
                "mkdir -p a/b && printf x > a/b/c.txt",
                "printf '*.tmp\\n' > .git/info/exclude && printf x > out.tmp",
                "mkdir w && printf '*\\n' > w/.gitignore && printf x > w/data",
                "printf y > new.md && git add new.md",
                "printf y > new.md && git add new.md && git commit -q -m local",
                "printf y > new.md && git add new.md && git commit -q -m local && git reset -q --hard \"$PROJECTION\"",
                "printf x >> article/index.md && git stash -q",
                "printf x >> article/index.md && git update-index --assume-unchanged article/index.md",
                "printf x >> article/index.md && git update-index --skip-worktree article/index.md",
                "git config core.fileMode false && chmod +x AGENTS.md",
                "git init -q inner"
            })
    void localWorkIsNeverReportedClean(String command) throws Exception {
        assertThat(inspectAfter(command)).isEmpty();
    }

    private String inspectAfter(String command) throws Exception {
        Path source = directory.resolve("source");
        Files.createDirectories(source.resolve("article"));
        Files.writeString(source.resolve("AGENTS.md"), "guide\n");
        Files.writeString(source.resolve("article/index.md"), "# Article\n");
        run(directory, "git", "init", "-q", "source");
        run(source, "git", "add", "-A");
        run(source, "git", "commit", "-q", "-m", "Public reading projection");
        run(source, "git", "branch", "snapshot");
        run(source, "git", "bundle", "create", "../snapshot.bundle", "snapshot");
        String commit = run(source, "git", "rev-parse", "HEAD").strip();
        run(
                directory,
                "git",
                "-c",
                "core.hooksPath=/dev/null",
                "clone",
                "--no-local",
                "--quiet",
                "snapshot.bundle",
                "copy");
        Path copy = directory.resolve("copy");
        run(copy, "git", "remote", "remove", "origin");
        run(copy, "git", "-c", "core.hooksPath=/dev/null", "checkout", "--quiet", "--detach", commit);
        run(copy, "/bin/bash", "-c", "PROJECTION=" + commit + "; " + command);
        Finished inspected = execute(copy, "/bin/bash", "-c", PublicCopyRefresh.inspection(commit));
        assertThat(inspected.exitCode()).isEqualTo(inspected.output().isEmpty() ? 1 : 0);
        return inspected.output();
    }

    private record Finished(int exitCode, String output) {}

    private String run(Path working, String... command) throws IOException, InterruptedException {
        Finished finished = execute(working, command);
        assertThat(finished.exitCode()).as(String.join(" ", command)).isZero();
        return finished.output();
    }

    // The launcher's Git environment ignores system and global configuration.
    private Finished execute(Path working, String... command) throws IOException, InterruptedException {
        var process = new ProcessBuilder(List.of(command))
                .directory(working.toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")));
        var environment = process.environment();
        environment.put("HOME", directory.toString());
        environment.put("GIT_CONFIG_NOSYSTEM", "1");
        environment.put("GIT_CONFIG_GLOBAL", "/dev/null");
        for (String role : List.of("AUTHOR", "COMMITTER")) {
            environment.put("GIT_" + role + "_NAME", "Poketto");
            environment.put("GIT_" + role + "_EMAIL", "poketto@invalid");
        }
        Process started = process.start();
        String output = new String(started.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(started.waitFor(30, TimeUnit.SECONDS)).isTrue();
        return new Finished(started.exitValue(), output);
    }
}
