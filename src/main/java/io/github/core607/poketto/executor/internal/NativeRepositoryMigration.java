package io.github.core607.poketto.executor.internal;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Offline operator-owned storage import; it neither impersonates a user nor executes repository commands. */
final class NativeRepositoryMigration {
    private static final Logger log = LoggerFactory.getLogger(NativeRepositoryMigration.class);
    private final SandcrabClient client;

    NativeRepositoryMigration(SandcrabClient client) {
        this.client = client;
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
        requireEligible(current);
        ProtocolValues.require(
                info.capabilities().containsAll(Set.of("sessions", "volumes", "files", "calls")),
                "import service",
                "must support repository storage and execution");
        SandcrabProtocol.Profile settings = info.profiles().get(profile);
        if (settings == null) {
            throw new IllegalArgumentException("Import requires an advertised native profile");
        }
        if (!settings.hostCalls()) {
            throw new IllegalArgumentException("Import requires a native profile with host calls");
        }
        ProtocolValues.require(
                workArchive.bytes() <= settings.transferBytes(),
                "import work archive",
                "exceeds the selected profile transfer bound");
        ProtocolValues.require(
                homeArchive.bytes() <= settings.transferBytes(),
                "import home archive",
                "exceeds the selected profile transfer bound");
        Part work = part(current, info, workArchive, profile, settings.sessionTtlMillis(), NativeRepositoryLayout.WORK);
        Part home = part(current, info, homeArchive, profile, settings.sessionTtlMillis(), NativeRepositoryLayout.HOME);
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

    static void requireEligible(AccountCopyRecord current) {
        Objects.requireNonNull(current, "Import requires an existing copy");
        ProtocolValues.require(current.writer().storage() == null, "import storage", "must still be legacy");
        ProtocolValues.require(current.phase() == AccountCopyRecord.Phase.READY, "import phase", "must be READY");
        ProtocolValues.require(current.executionId() == null, "import execution", "must be absent");
        ProtocolValues.require(current.expiresAt() > System.currentTimeMillis(), "import expiry", "must remain live");
        ProtocolValues.require(current.state().move() == null, "import move", "must be explicitly completed first");
        ProtocolValues.require(current.state().sync() == null, "import sync", "must be explicitly completed first");
        ProtocolValues.require(!current.state().uncertain(), "import save", "must be explicitly reconciled first");
    }

    private static Part part(
            AccountCopyRecord current,
            SandcrabProtocol.Info info,
            Archive archive,
            String profile,
            long ttlMillis,
            String path) {
        String session = UUID.randomUUID().toString();
        return new Part(
                new SandcrabProtocol.SessionPlan(
                        session,
                        info.serverId(),
                        profile,
                        ttlMillis,
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
     * The app-UID operator holds the account journal and an inherited exclusive source FLOCK. The
     * root controller continuously fences legacy admission and verifies containment through the callback.
     */
    void importCopy(AccountCommand held, Plan plan, Path workArchive, Path homeArchive, Runnable requireContained) {
        requireContained.run();
        if (alreadyImported(held.record(), plan)) {
            return;
        }
        ProtocolValues.require(plan.expected().equals(held.record()), "import journal", "changed since planning");
        requireEligible(held.record());
        held.requireLive();
        SandcrabVolume volume = ready(client.createVolume(plan.volume()), requireContained);
        plan.writer().storage().requireVolume(volume);
        SandcrabProtocol.Upload work = importPart(plan.work(), workArchive, requireContained);
        SandcrabProtocol.Upload home = importPart(plan.home(), homeArchive, requireContained);
        requireContained.run();
        requireEligible(held.record());
        held.bind(plan.writer());
        requirePreserved(held.record(), plan);
        forget(work);
        forget(home);
    }

    private SandcrabProtocol.Upload importPart(Part part, Path archive, Runnable requireContained) {
        requireContained.run();
        SandcrabProtocol.Upload upload = existing(part);
        if (upload == null) {
            upload = transfer(part, archive, requireContained);
        }
        requireImported(part, upload);
        contain(part);
        return upload;
    }

    private static boolean alreadyImported(AccountCopyRecord current, Plan plan) {
        if (current == null || !current.writer().equals(plan.writer())) {
            return false;
        }
        requirePreserved(current, plan);
        return true;
    }

    private static void requirePreserved(AccountCopyRecord current, Plan plan) {
        ProtocolValues.require(current.owner().equals(plan.expected().owner()), "imported owner", "must be preserved");
        ProtocolValues.require(
                current.expiresAt() == plan.expected().expiresAt(), "imported expiry", "must be preserved");
        ProtocolValues.require(
                Objects.equals(current.publicExport(), plan.expected().publicExport()),
                "imported projection",
                "must be preserved");
        ProtocolValues.require(
                current.phase() == AccountCopyRecord.Phase.READY && current.executionId() == null,
                "imported phase",
                "must remain idle and ready");
        ProtocolValues.require(
                Objects.equals(current.lastInterruptedCommand(), plan.expected().lastInterruptedCommand()),
                "imported interruption",
                "must be preserved");
        ProtocolValues.require(
                current.copyId().equals(plan.expected().copyId()), "imported identity", "must be preserved");
        ProtocolValues.require(
                current.state().equals(plan.expected().state()), "imported save state", "must be preserved");
        ProtocolValues.require(
                Objects.equals(current.original(), plan.expected().original()),
                "imported original baseline",
                "must be preserved");
    }

    private SandcrabVolume ready(SandcrabVolume volume, Runnable requireContained) {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!volume.state().equals("ready")) {
            if (!volume.state().equals("creating")) {
                throw new WorkerUnavailableException();
            }
            NativeRepositoryLease.pause(deadline, requireContained);
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

    private SandcrabProtocol.Upload transfer(Part plan, Path archive, Runnable requireContained) {
        boolean opened = false;
        try {
            SandcrabProtocol.Session session = client.open(plan.session());
            opened = true;
            long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
            while (!session.state().equals("ready")) {
                if (session.terminal()) {
                    throw new WorkerUnavailableException();
                }
                NativeRepositoryLease.pause(deadline, requireContained);
                session = client.session(plan.session().reference());
            }
            requireContained.run();
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
