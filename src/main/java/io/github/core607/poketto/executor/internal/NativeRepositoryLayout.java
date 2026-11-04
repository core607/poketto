package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.content.RepositoryPaths;

/** Poketto's persistent roots inside a generic Sandcrab volume. */
final class NativeRepositoryLayout {
    static final String WORK = "work";
    static final String HOME = "home";
    static final String REPOSITORY = WORK + "/repository";
    static final String SEED = "seed.bundle";
    private static final String MOUNT = "/workspace/";

    private NativeRepositoryLayout() {}

    static String repository(String path) {
        return REPOSITORY + "/" + RepositoryPaths.validate(path);
    }

    static String attach() {
        return NativeGitBaseline.CLEAN + " && export HOME=" + MOUNT + HOME + " && cd " + MOUNT + REPOSITORY;
    }

    static String commitTemplate(String commit) {
        String revision = ProtocolValues.hex(commit, 40, "export commit");
        // read-tree rebuilds the index without machine-specific checkout timestamps and inode numbers.
        return """
                set -eu
                cd /workspace
                umask 077
                mkdir -m 700 work home work/repository
                export HOME=/workspace/home GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null GIT_ATTR_NOSYSTEM=1
                cd work/repository
                git init --quiet --template= --initial-branch=snapshot
                git config core.logAllRefUpdates false
                git -c transfer.unpackLimit=0 bundle unbundle /workspace/seed.bundle >/dev/null
                git -c core.hooksPath=/dev/null checkout --quiet --detach %1$s
                rm .git/index
                git read-tree %1$s
                rm /workspace/seed.bundle
                """.formatted(revision);
    }
}
