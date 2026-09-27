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
 * Runs the application's fixed inspection with real Git and Bash in a copy laid out the way the
 * worker and its launcher create it: an empty home, and a work directory whose only entry is a
 * clone of the projection bundle without a remote, detached at the projection commit. It needs Linux
 * tools, so it runs where CI runs the unit tests.
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
                "git init -q inner",
                "printf x > \"$HOME/report.md\"",
                "mkdir -p \"$HOME/.cache/fontconfig\"",
                "printf x > ../draft.md",
                "mkdir ../notes",
                "git worktree add -q ../x",
                "git worktree add -q --detach ../y"
            })
    void localWorkIsNeverReportedClean(String command) throws Exception {
        assertThat(inspectAfter(command)).isEmpty();
    }

    // The worker creates an empty home and work directory in the copy root, and the launcher clones
    // the projection into work/repository with HOME set to that home.
    private String inspectAfter(String command) throws Exception {
        Path source = directory.resolve("source");
        Files.createDirectories(source.resolve("article"));
        Files.writeString(source.resolve("AGENTS.md"), "guide\n");
        Files.writeString(source.resolve("article/index.md"), "# Article\n");
        Path work = Files.createDirectories(directory.resolve("copy/work"));
        Files.createDirectories(home());
        run(directory, "git", "init", "-q", "source");
        run(source, "git", "add", "-A");
        run(source, "git", "commit", "-q", "-m", "Public reading projection");
        run(source, "git", "branch", "snapshot");
        run(source, "git", "bundle", "create", "../snapshot.bundle", "snapshot");
        String commit = run(source, "git", "rev-parse", "HEAD").strip();
        String bundle = directory.resolve("snapshot.bundle").toString();
        run(work, "git", "-c", "core.hooksPath=/dev/null", "clone", "--no-local", "--quiet", bundle, "repository");
        Path repository = work.resolve("repository");
        run(repository, "git", "remote", "remove", "origin");
        run(repository, "git", "-c", "core.hooksPath=/dev/null", "checkout", "--quiet", "--detach", commit);
        run(repository, "/bin/bash", "-c", "PROJECTION=" + commit + "; " + command);
        Finished inspected = execute(repository, "/bin/bash", "-c", PublicCopyRefresh.inspection(commit));
        assertThat(inspected.exitCode()).isEqualTo(inspected.output().isEmpty() ? 1 : 0);
        return inspected.output();
    }

    private Path home() {
        return directory.resolve("copy/home");
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
        environment.put("HOME", home().toString());
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
