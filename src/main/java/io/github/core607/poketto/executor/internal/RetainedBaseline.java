package io.github.core607.poketto.executor.internal;

import java.util.Objects;
import java.util.UUID;

/** Immutable original-baseline authority; metadata records retain only this reference. */
final class RetainedBaseline {
    private RetainedBaseline() {}

    record Identity(AccountCopyRecord.Owner owner, UUID copyId, String commit) {
        Identity {
            Objects.requireNonNull(owner, "baseline owner must be present");
            ProtocolValues.require(owner.fullRead(), "baseline scope", "must be a full copy");
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
