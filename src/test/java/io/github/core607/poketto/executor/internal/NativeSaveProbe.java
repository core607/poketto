package io.github.core607.poketto.executor.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.core607.poketto.mcp.RepositoryExecutor;
import java.util.function.BiFunction;
import java.util.function.IntSupplier;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A real host, sandbox Git and two remote pushes share the same named account copy. */
record NativeSaveProbe(BiFunction<String, String, RepositoryExecutor.ExecutionResult> execute) {
    private static final JsonMapper JSON = new JsonMapper();

    String saveAndRecover(IntSupplier pushes) {
        var first = execute.apply(RepositoryExecutor.NEW_COPY, """
                set -eu
                printf local > private/local.md
                git add private/local.md
                git -c user.name=Fixture -c user.email=fixture@example.invalid commit -qm checkpoint
                git rev-parse HEAD > "$HOME/local-checkpoint"
                printf '\\000\\377' > private/dirty.bin
                printf 'selected change' > private/selected.md
                poketto save private/selected.md
                """);
        String copy = first.copyId();
        String saved = success(first).path("commit").asString();
        assertThat(first.commit()).isEqualTo(saved);
        var head = execute.apply(copy, "git rev-parse HEAD");
        assertThat(head.stdout().strip()).isEqualTo(saved);
        assertThat(head.commit()).isEqualTo(saved);
        assertThat(pushes.getAsInt()).isOne();

        var blocked = execute.apply(copy, """
                set -eu
                printf index-only > private/staged.md
                git add private/staged.md
                printf work-only > private/staged.md
                printf second > private/second.md
                printf foreign-lock > .git/index.lock
                poketto save private/second.md
                """);
        pending(blocked, saved);
        var status = success(execute.apply(copy, "poketto status"));
        String target = status.path("baseCommit").asString();
        assertThat(target).matches("[0-9a-f]{40}").isNotEqualTo(saved);
        assertThat(status.path("gitCommit").asString()).isEqualTo(saved);
        assertThat(status.path("localBaselinePending").booleanValue()).isTrue();
        assertThat(pushes.getAsInt()).isEqualTo(2);

        pending(execute.apply(copy, "poketto save private/second.md"), saved);
        verifyRecovery(copy, target);
        assertThat(pushes.getAsInt()).isEqualTo(2);
        return copy;
    }

    String missingGitPrerequisite(IntSupplier pushes) {
        var written = execute.apply(RepositoryExecutor.NEW_COPY, """
                set -eu
                test "$(pwd)" = /workspace/work/repository
                previous=$(git rev-parse HEAD)
                mv .git "$(mktemp -d)/previous"
                git init --quiet --template= --initial-branch=local
                git -c user.name=Fixture -c user.email=fixture@example.invalid commit --allow-empty -qm independent
                if git cat-file -e "$previous^{commit}" 2>/dev/null; then exit 1; fi
                printf fallback > private/missing-base.md
                poketto save private/missing-base.md
                """);
        String target = success(written).path("commit").asString();
        assertThat(written.commit()).isEqualTo(target);
        var next = execute.apply(written.copyId(), "set -eu; git cat-file -e HEAD^; git rev-parse HEAD");
        assertThat(next.exitCode()).as(next.stderr()).isZero();
        assertThat(next.stdout().strip()).isEqualTo(target);
        assertThat(pushes.getAsInt()).isOne();
        return written.copyId();
    }

    private void verifyRecovery(String copy, String target) {
        var recovered = execute.apply(copy, """
                set -eu
                test "$(cat .git/index.lock)" = foreign-lock
                rm .git/index.lock
                poketto recover
                """);
        success(recovered);
        assertThat(recovered.commit()).isEqualTo(target);
        var checked = execute.apply(copy, """
                set -eu
                git cat-file -e "$(cat "$HOME/local-checkpoint")^{commit}"
                test "$(git show 'stash@{0}^2:private/staged.md')" = index-only
                test "$(cat private/staged.md)" = work-only
                test "$(od -An -tu1 private/dirty.bin | xargs)" = '0 255'
                test -z "$(git status --porcelain -- private/selected.md private/second.md)"
                test ! -e /workspace/.poketto-git-baseline.bundle
                git rev-parse HEAD
                """);
        assertThat(checked.exitCode()).as(checked.stderr()).isZero();
        assertThat(checked.stdout().strip()).isEqualTo(target);
        JsonNode status = success(execute.apply(copy, "poketto status"));
        assertThat(status.path("gitCommit").asString()).isEqualTo(target);
        assertThat(status.path("localBaselinePending").booleanValue()).isFalse();
    }

    private static JsonNode success(RepositoryExecutor.ExecutionResult result) {
        assertThat(result.exitCode()).as(result.stderr() + result.stdout()).isZero();
        var reply = JSON.readTree(result.stdout());
        assertThat(reply.path("ok").booleanValue()).isTrue();
        return reply.path("result");
    }

    private static void pending(RepositoryExecutor.ExecutionResult result, String installed) {
        assertThat(result.exitCode()).as(result.stderr() + result.stdout()).isOne();
        assertThat(JSON.readTree(result.stdout()).path("code").asString()).isEqualTo("LOCAL_BASELINE_PENDING");
        assertThat(result.commit()).isEqualTo(installed);
    }
}
