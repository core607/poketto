package io.github.core607.poketto.content;

/** Host-retained commit bytes identify one uncertain write; never reconstructed from sandbox Git. */
public record RepositoryWriteAttempt(String commit, byte[] object) {
    public RepositoryWriteAttempt {
        if (commit == null
                || !commit.matches("[0-9a-f]{40}")
                || object == null
                || object.length == 0
                || object.length > 16384)
            throw new IllegalArgumentException("invalid bounded repository write attempt");
        object = object.clone();
    }

    @Override
    public byte[] object() {
        return object.clone();
    }
}
