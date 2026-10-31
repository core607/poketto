package io.github.core607.poketto.executor.internal;

import java.util.Objects;
import java.util.UUID;

/** A pool admission's fixed identity; native acknowledgements cannot change its copy, grant or storage. */
record NativeRepositoryPoolBinding(NativeRepositoryPool.Key key, UUID subject, SandcrabProtocol.Info info) {
    NativeRepositoryPoolBinding {
        Objects.requireNonNull(key, "pooled copy key must be present");
        Objects.requireNonNull(subject, "pooled grant identity must be present");
        Objects.requireNonNull(info, "pooled native information must be present");
    }

    void validate(
            AccountCopyRecord.Writer expected,
            SandcrabProtocol.SessionPlan plan,
            AccountCopyRecord record,
            SandcrabProtocol.Session session) {
        Objects.requireNonNull(plan, "pooled session plan must precede acknowledgement");
        Objects.requireNonNull(record, "pooled copy record must be present");
        ProtocolValues.require(
                record.owner().equals(key.owner()) && record.copyId().equals(key.copyId()),
                "pooled copy",
                "must preserve its owner and identity");
        AccountCopyRecord.Writer writer = record.writer();
        AccountCopyRecord.NativeStorage storage =
                Objects.requireNonNull(writer.storage(), "pooled native storage must be present");
        ProtocolValues.require(
                writer.principalId().equals(subject)
                        && writer.workerBootId().toString().equals(info.serverId())
                        && writer.leaseId().toString().equals(plan.id()),
                "pooled writer",
                "must preserve its grant, epoch and session identity");
        ProtocolValues.require(
                plan.serverId().equals(info.serverId())
                        && plan.profile().equals(storage.profile())
                        && Objects.equals(plan.volumeId(), storage.volumeId().toString()),
                "pooled session plan",
                "must preserve its volume and profile");
        SandcrabProtocol.Profile profile = info.profiles().get(plan.profile());
        ProtocolValues.require(
                profile != null && plan.ttlMillis() <= profile.sessionTtlMillis(),
                "pooled session lifetime",
                "must fit the operator profile");
        if (expected != null) {
            ProtocolValues.require(expected.equals(writer), "pooled writer", "must remain bound to its journal");
        }
        if (session != null) {
            ProtocolValues.require(
                    plan.reference().equals(session.reference())
                            && plan.profile().equals(session.profile())
                            && Objects.equals(plan.volumeId(), session.volumeId())
                            && session.parentSessionId() == null,
                    "pooled native session",
                    "must match the registered workspace identity");
        }
    }
}
