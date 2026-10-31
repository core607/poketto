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
        return "export HOME=" + MOUNT + HOME + " && cd " + MOUNT + REPOSITORY;
    }

    static String initialize(String commit) {
        return "mkdir -m 700 " + MOUNT + WORK + " " + MOUNT + HOME
                + " && export HOME=" + MOUNT + HOME
                + " && git clone --quiet --no-checkout --no-hardlinks " + MOUNT + SEED + " " + MOUNT + REPOSITORY
                + " && git -C " + MOUNT + REPOSITORY + " checkout --quiet --detach "
                + ProtocolValues.hex(commit, 40, "export commit")
                + " && rm " + MOUNT + SEED + " && cd " + MOUNT + REPOSITORY;
    }
}
