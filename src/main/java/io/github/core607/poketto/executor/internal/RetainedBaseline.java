package io.github.core607.poketto.executor.internal;

import java.util.Objects;
import java.util.UUID;

/** Immutable original-baseline authority; metadata records retain only this reference. */
final class RetainedBaseline {
    private RetainedBaseline() {}

    static void validateBinding(
            RetainedCopyRecord.Owner owner, UUID copyId, boolean fullRead, String commit, Reference reference) {
        if (!fullRead) {
            ProtocolValues.require(reference == null, "public original baseline", "must not contain private data");
            return;
        }
        Objects.requireNonNull(reference, "full copy original baseline must be present");
        ProtocolValues.require(
                reference.identity().equals(new Identity(owner, copyId, commit)),
                "original baseline identity",
                "must match the copy owner and original commit");
    }

    record Identity(RetainedCopyRecord.Owner owner, UUID copyId, String commit) {
        Identity {
            Objects.requireNonNull(owner, "baseline owner must be present");
            Objects.requireNonNull(copyId, "baseline copy must be present");
            commit = ProtocolValues.hex(commit, 40, "baseline commit");
        }
    }

    record Reference(Identity identity, String sha256, long bytes, int entries) {
        Reference {
            Objects.requireNonNull(identity, "baseline identity must be present");
            sha256 = ProtocolValues.hex(sha256, 64, "baseline digest");
            ProtocolValues.inRange(bytes, 64, 1024L * 1024 * 1024, "baseline archive bytes");
            ProtocolValues.inRange(entries, 0, 100_000, "baseline entry count");
        }
    }

    record Limits(long archiveBytes, long expandedBytes, int entries) {
        Limits {
            ProtocolValues.inRange(archiveBytes, 4096, 1024L * 1024 * 1024, "baseline archive limit");
            ProtocolValues.inRange(expandedBytes, 4096, 8L * 1024 * 1024 * 1024, "baseline expanded limit");
            ProtocolValues.inRange(entries, 1, 100_000, "baseline entries limit");
        }
    }
}
