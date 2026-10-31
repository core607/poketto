package io.github.core607.poketto.executor.internal;

import java.util.Objects;

/** Durable copy identity and baseline transitions shared by storage and its peer fixtures. */
final class AccountCopyTransitions {
    private AccountCopyTransitions() {}

    /** The journal's transition rules; the socket tests' in-memory journal applies the same ones. */
    static void requireTransition(
            AccountCopyRecord.Owner owner, AccountCopyRecord current, AccountCopyRecord next, long nextExpiry) {
        ProtocolValues.require(next.owner().equals(owner), "copy owner", "must match the held account");
        ProtocolValues.require(
                next.revision() == (current == null ? 0 : current.revision() + 1),
                "copy revision",
                "must advance the held journal");
        ProtocolValues.require(
                current == null || current.copyId().equals(next.copyId()),
                "copy identity",
                "must not replace existing work");
        if (current != null) {
            ProtocolValues.require(
                    current.writer().storage() == null
                            || current.writer().storage().equals(next.writer().storage()),
                    "native storage",
                    "must preserve the retained volume binding");
            ProtocolValues.require(
                    current.phase() != AccountCopyRecord.Phase.DISCARDING
                            || next.phase() == AccountCopyRecord.Phase.DISCARDING,
                    "discarded copy",
                    "must not become executable again");
            // Only a public copy's rebuild re-pins its commit and projection, in one write from
            // REFRESHING to REFRESHING, so the copy ID never leaves the journal.
            boolean rebuilt = !owner.fullRead()
                    && current.phase() == AccountCopyRecord.Phase.REFRESHING
                    && next.phase() == AccountCopyRecord.Phase.REFRESHING;
            ProtocolValues.require(
                    rebuilt
                            || current.state()
                                    .originalCommit()
                                    .equals(next.state().originalCommit()),
                    "original commit",
                    "must remain pinned");
            ProtocolValues.require(
                    current.original() == null || current.original().equals(next.original()),
                    "original archive",
                    "must remain immutable");
            ProtocolValues.require(
                    rebuilt || Objects.equals(current.publicExport(), next.publicExport()),
                    "public projection",
                    "must remain pinned");
            ProtocolValues.require(next.expiresAt() >= current.expiresAt(), "copy expiry", "must not move backwards");
        }
        long latest = Math.max(nextExpiry, current == null ? 0 : current.expiresAt());
        ProtocolValues.require(next.expiresAt() <= latest, "copy expiry", "exceeds idle retention");
        requirePlanTransition(
                current == null ? null : current.state().nativePlan(),
                next.state().nativePlan());
    }

    private static void requirePlanTransition(NativeRepositoryPlan current, NativeRepositoryPlan next) {
        if (current == null || next == null) {
            return;
        }
        ProtocolValues.require(
                current.id().equals(next.id()), "plan identity", "requires explicit release before replacement");
        ProtocolValues.require(
                current.kind() == next.kind()
                        && current.baseCommit().equals(next.baseCommit())
                        && current.remoteCommit().equals(next.remoteCommit())
                        && Objects.equals(current.move(), next.move())
                        && current.paths().equals(next.paths()),
                "plan authority",
                "must retain its captured inventory and commits");
        ProtocolValues.require(!current.prepared() || next.prepared(), "prepared plan", "must not become incomplete");
        ProtocolValues.require(
                next.files().entrySet().containsAll(current.files().entrySet()),
                "plan originals",
                "must preserve every prepared file");
        ProtocolValues.require(
                next.accepted().keySet().containsAll(current.accepted().keySet()),
                "plan acceptance",
                "must not lose acknowledged progress");
        ProtocolValues.require(
                next.staged().containsAll(current.staged()), "plan staging", "must not lose acknowledged progress");
    }
}
