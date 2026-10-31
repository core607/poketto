package io.github.core607.poketto.executor.internal;

import io.github.core607.poketto.auth.AuthPrincipal;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Imports a contained copy without deleting its source or replacing its application-owned save history. */
final class NativeRepositoryMigration {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryMigration.class);
    private final SandcrabClient client;
    private final NativeRepositoryWorkspace workspaces;

    NativeRepositoryMigration(SandcrabClient client, NativeRepositoryWorkspace workspaces) {
        this.client = client;
        this.workspaces = workspaces;
    }

    /** The operator persists this exact plan beside both unchanged archives before calling importCopy. */
    static Plan plan(
            AccountCopyRecord current,
            SandcrabProtocol.Info info,
            Archive workArchive,
            Archive homeArchive,
            String profile,
            long byteLimit,
            long inodeLimit) {
        if (current.writer().storage() != null || current.phase() == AccountCopyRecord.Phase.DISCARDING) {
            throw new IllegalArgumentException("Import requires a retained legacy copy that is not being discarded");
        }
        if (current.state().move() != null || current.state().sync() != null) {
            throw new IllegalArgumentException(
                    "Complete the retained move or synchronization before importing its copy");
        }
        Part work = part(current, info, workArchive, profile, NativeRepositoryLayout.WORK);
        Part home = part(current, info, homeArchive, profile, NativeRepositoryLayout.HOME);
        var storage = new AccountCopyRecord.NativeStorage(current.copyId(), profile, byteLimit, inodeLimit);
        var writer = new AccountCopyRecord.Writer(
                current.writer().principalId(),
                UUID.fromString(info.serverId()),
                UUID.randomUUID(),
                UUID.fromString(home.session().id()),
                storage);
        return new Plan(
                current,
                writer,
                new SandcrabVolume.Plan(
                        current.copyId().toString(), info.serverId(), profile, null, byteLimit, inodeLimit),
                work,
                home);
    }

    private static Part part(
            AccountCopyRecord current, SandcrabProtocol.Info info, Archive archive, String profile, String path) {
        String session = UUID.randomUUID().toString();
        return new Part(
                new SandcrabProtocol.SessionPlan(
                        session,
                        info.serverId(),
                        profile,
                        120_000,
                        current.copyId().toString()),
                new SandcrabProtocol.UploadPlan(
                        UUID.randomUUID().toString(),
                        info.serverId(),
                        session,
                        path,
                        "tar",
                        archive.bytes(),
                        archive.sha256(),
                        false,
                        true,
                        0700));
    }

    /**
     * The caller holds the account journal and the operator's exclusive migration lock. The fence
     * verifies actual legacy process containment before any transfer and again before journal publication.
     */
    void importCopy(
            AuthPrincipal principal,
            AccountCommand held,
            Plan plan,
            Path workArchive,
            Path homeArchive,
            Runnable requireContained) {
        workspaces.authorize(principal, held.owner(), held.record());
        if (alreadyImported(held.record(), plan)) {
            return;
        }
        ProtocolValues.require(plan.expected().equals(held.record()), "import journal", "changed since planning");
        requireContained.run();
        workspaces.authorize(principal, held.owner(), held.record());
        SandcrabVolume volume = ready(client.createVolume(plan.volume()), principal, held);
        plan.writer().storage().requireVolume(volume);
        SandcrabProtocol.Upload work = importPart(plan.work(), workArchive, principal, held);
        SandcrabProtocol.Upload home = importPart(plan.home(), homeArchive, principal, held);
        requireContained.run();
        workspaces.authorize(principal, held.owner(), held.record());
        held.bind(plan.writer());
        forget(work);
        forget(home);
    }

    private SandcrabProtocol.Upload importPart(Part part, Path archive, AuthPrincipal principal, AccountCommand held) {
        workspaces.authorize(principal, held.owner(), held.record());
        SandcrabProtocol.Upload upload = existing(part);
        if (upload == null) {
            upload = transfer(part, archive, principal, held);
        }
        requireImported(part, upload);
        contain(part);
        return upload;
    }

    private static boolean alreadyImported(AccountCopyRecord current, Plan plan) {
        if (current == null || !current.writer().equals(plan.writer())) {
            return false;
        }
        ProtocolValues.require(
                current.copyId().equals(plan.expected().copyId()), "imported identity", "must be preserved");
        ProtocolValues.require(
                current.state().equals(plan.expected().state()), "imported save state", "must be preserved");
        ProtocolValues.require(
                Objects.equals(current.original(), plan.expected().original()),
                "imported original baseline",
                "must be preserved");
        return true;
    }

    private SandcrabVolume ready(SandcrabVolume volume, AuthPrincipal principal, AccountCommand held) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!volume.state().equals("ready")) {
            if (!volume.state().equals("creating")) {
                throw new WorkerUnavailableException();
            }
            NativeRepositoryLease.pause(deadline, () -> workspaces.authorize(principal, held.owner(), held.record()));
            volume = client.volume(volume);
        }
        return volume;
    }

    private SandcrabProtocol.Upload existing(Part plan) {
        try {
            return client.upload(plan.upload().reference());
        } catch (SandcrabClient.Rejected missing) {
            if (GameSandboxJob.missing(missing, "UPLOAD_NOT_FOUND")) {
                return null;
            }
            throw missing;
        }
    }

    private SandcrabProtocol.Upload transfer(Part plan, Path archive, AuthPrincipal principal, AccountCommand held) {
        boolean opened = false;
        try {
            SandcrabProtocol.Session session = client.open(plan.session());
            opened = true;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!session.state().equals("ready")) {
                if (session.terminal()) {
                    throw new WorkerUnavailableException();
                }
                NativeRepositoryLease.pause(
                        deadline, () -> workspaces.authorize(principal, held.owner(), held.record()));
                session = client.session(plan.session().reference());
            }
            workspaces.authorize(principal, held.owner(), held.record());
            client.register(session, plan.upload());
            return client.send(plan.upload(), archive);
        } catch (RuntimeException failure) {
            if (opened) {
                try {
                    contain(plan);
                } catch (WorkerUnavailableException | SandcrabClient.Rejected uncontained) {
                    failure.addSuppressed(uncontained);
                }
            }
            throw failure;
        }
    }

    private static void requireImported(Part plan, SandcrabProtocol.Upload upload) {
        ProtocolValues.require(upload.succeeded(), "import upload", "must confirm durable publication");
        ProtocolValues.require(
                plan.upload().reference().equals(upload.reference()), "import receipt", "must retain its identity");
        ProtocolValues.require(
                plan.session().volumeId().equals(upload.volumeId()), "import receipt", "must name the retained volume");
        ProtocolValues.require(
                plan.session().id().equals(upload.sessionId())
                        && plan.session().profile().equals(upload.profile()),
                "import receipt",
                "must name the planned session and profile");
        ProtocolValues.require(
                upload.path().equals(plan.upload().path()) && upload.format().equals("tar"),
                "import layout",
                "must preserve its complete retained directory");
        ProtocolValues.require(
                upload.bytes() == plan.upload().bytes()
                        && upload.sha256().equals(plan.upload().sha256()),
                "import content",
                "must match the retained source archive");
    }

    private void contain(Part plan) {
        if (client.info().serverId().equals(plan.session().serverId())) {
            NativeRepositoryLease.close(client, plan.session().reference(), Duration.ofSeconds(15));
        }
    }

    private void forget(SandcrabProtocol.Upload upload) {
        try {
            client.forget(upload);
        } catch (WorkerUnavailableException | SandcrabClient.Rejected failure) {
            log.warn("Imported copy is durable; its upload receipt remains for operator reconciliation", failure);
        }
    }

    record Archive(long bytes, String sha256) {
        Archive {
            ProtocolValues.inRange(bytes, 1024, 1_073_741_824, "import archive bytes");
            sha256 = ProtocolValues.hex(sha256, 64, "import archive digest");
        }
    }

    record Plan(
            AccountCopyRecord expected,
            AccountCopyRecord.Writer writer,
            SandcrabVolume.Plan volume,
            Part work,
            Part home) {
        Plan {
            Objects.requireNonNull(expected, "import source journal must be retained");
            Objects.requireNonNull(writer, "import writer must be retained");
            Objects.requireNonNull(volume, "import volume plan must be retained");
            Objects.requireNonNull(work, "import work plan must be retained");
            Objects.requireNonNull(home, "import home plan must be retained");
            requireBindings(expected, writer, volume);
            work.requireBindings(writer, volume, NativeRepositoryLayout.WORK);
            home.requireBindings(writer, volume, NativeRepositoryLayout.HOME);
            ProtocolValues.require(
                    home.session().id().equals(writer.leaseId().toString()),
                    "import writer",
                    "must match the final contained session");
            ProtocolValues.require(
                    !work.session().id().equals(home.session().id()),
                    "import sessions",
                    "must allow independent completion after a lost transfer reply");
            ProtocolValues.require(
                    !work.upload().id().equals(home.upload().id()),
                    "import uploads",
                    "must retain separate receipt identities");
        }

        private static void requireBindings(
                AccountCopyRecord expected, AccountCopyRecord.Writer writer, SandcrabVolume.Plan volume) {
            var storage = Objects.requireNonNull(writer.storage(), "import requires native storage");
            ProtocolValues.require(
                    storage.volumeId().equals(expected.copyId()), "import storage", "must preserve the copy ID");
            ProtocolValues.require(
                    volume.id().equals(expected.copyId().toString()), "import volume", "must match its journal");
            ProtocolValues.require(
                    storage.profile().equals(volume.profile()), "import profile", "must match every planned resource");
            ProtocolValues.require(
                    writer.workerBootId().toString().equals(volume.serverId()),
                    "import epoch",
                    "must match every planned request");
            ProtocolValues.require(
                    volume.byteLimit() != null && volume.byteLimit() == storage.byteLimit(),
                    "import byte limit",
                    "must match its journal");
            ProtocolValues.require(
                    volume.inodeLimit() != null && volume.inodeLimit() == storage.inodeLimit(),
                    "import inode limit",
                    "must match its journal");
            ProtocolValues.require(
                    volume.cloneFrom() == null,
                    "import source",
                    "must populate an empty volume from retained archives");
        }
    }

    record Part(SandcrabProtocol.SessionPlan session, SandcrabProtocol.UploadPlan upload) {
        Part {
            Objects.requireNonNull(session, "import session plan must be retained");
            Objects.requireNonNull(upload, "import upload plan must be retained");
        }

        void requireBindings(AccountCopyRecord.Writer writer, SandcrabVolume.Plan volume, String path) {
            ProtocolValues.require(
                    volume.id().equals(session.volumeId()), "import session", "must attach its planned volume");
            ProtocolValues.require(
                    session.id().equals(upload.sessionId()), "import upload", "must target its planned session");
            ProtocolValues.require(
                    volume.profile().equals(session.profile()), "import profile", "must match its volume");
            ProtocolValues.require(
                    writer.workerBootId().toString().equals(session.serverId())
                            && session.serverId().equals(upload.serverId()),
                    "import epoch",
                    "must match every planned request");
            ProtocolValues.require(
                    upload.path().equals(path) && upload.format().equals("tar"),
                    "import source",
                    "must populate its retained directory");
            ProtocolValues.require(
                    !upload.replace() && upload.freeze(), "import installation", "must not overwrite existing work");
        }
    }
}
