package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** Offline app-UID entry point, launched only by the privileged migration controller. */
public final class NativeRepositoryMigrationOperator {
    private NativeRepositoryMigrationOperator() {}

    public static void main(String[] args) {
        try {
            ProtocolValues.require(args.length == 1, "operator arguments", "require one protected settings file");
            Settings settings = settings(Path.of(args[0]));
            var client = ExecutorConfiguration.sandcrabClient(settings.socket());
            var store =
                    new AccountCopyStore(settings.metadata(), settings.limits().limits(), Clock.systemUTC());
            try (var control = new NativeMigrationControl(settings.operation(), System.in, System.out);
                    AccountCommand held = AccountCommand.ifIdle(store, settings.owner())
                            .orElseThrow(() -> new IllegalStateException("Operator journal is busy"))) {
                run(settings, held, client, control);
            }
        } catch (RuntimeException | IOException failure) {
            // The controller owns private diagnostics; user data and operator paths never enter console output.
            System.err.println("Native repository migration did not complete; reconcile its fixed private operation");
            System.exit(1);
        }
    }

    static void run(Settings settings, AccountCommand held, SandcrabClient client, NativeMigrationControl control)
            throws IOException {
        Runnable fence = () -> control.fence(settings.sourceLock(), settings.deploymentLock());
        fence.run();
        AccountCopyRecord current = Objects.requireNonNull(held.record(), "Migration copy must already exist");
        ProtocolValues.require(
                current.owner().equals(settings.owner()) && current.copyId().equals(settings.copyId()),
                "operator copy",
                "must match the held journal");
        if (current.writer().storage() == null) {
            NativeRepositoryMigration.requireEligible(current);
        }
        control.send("locked", current, null);
        NativeMigrationControl.Reply input = control.receive("archives");
        NativeMigrationControl.Archives archives = Objects.requireNonNull(input.archives(), "Archives must be fixed");
        verifyArchive(settings.staging().resolve("work.tar"), archives.work());
        verifyArchive(settings.staging().resolve("home.tar"), archives.home());
        NativeRepositoryMigration.Plan plan = input.plan() == null
                ? NativeRepositoryMigration.plan(
                        current,
                        client.info(),
                        archives.work(),
                        archives.home(),
                        settings.profile(),
                        settings.volumeBytes(),
                        settings.volumeInodes())
                : input.plan();
        requirePlan(settings, plan, archives);
        control.send("plan", null, plan);
        NativeMigrationControl.Reply decision = control.receive("plan");
        fence.run();
        if (!decision.execute()) {
            ProtocolValues.require(current.equals(plan.expected()), "prepared journal", "changed since planning");
            NativeRepositoryMigration.requireEligible(current);
            control.send("prepared", current, plan);
            return;
        }
        ProtocolValues.require(input.plan() != null, "operator execution", "requires its previously persisted plan");
        new NativeRepositoryMigration(client)
                .importCopy(
                        held,
                        plan,
                        settings.staging().resolve("work.tar"),
                        settings.staging().resolve("home.tar"),
                        fence);
        control.send("published", held.record(), plan);
    }

    private static void requirePlan(
            Settings settings, NativeRepositoryMigration.Plan plan, NativeMigrationControl.Archives archives) {
        ProtocolValues.require(
                plan.expected().owner().equals(settings.owner())
                        && plan.expected().copyId().equals(settings.copyId()),
                "operator plan owner",
                "must match the requested retained copy");
        ProtocolValues.require(
                plan.volume().profile().equals(settings.profile())
                        && plan.volume().byteLimit() == settings.volumeBytes()
                        && plan.volume().inodeLimit() == settings.volumeInodes(),
                "operator storage plan",
                "must retain the protected bounds");
        ProtocolValues.require(
                archives.work().bytes() == plan.work().upload().bytes()
                        && archives.work().sha256().equals(plan.work().upload().sha256()),
                "operator work archive",
                "must match the fixed plan");
        ProtocolValues.require(
                archives.home().bytes() == plan.home().upload().bytes()
                        && archives.home().sha256().equals(plan.home().upload().sha256()),
                "operator home archive",
                "must match the fixed plan");
    }

    private static Settings settings(Path path) throws IOException {
        ProtocolValues.require(
                path.isAbsolute() && path.normalize().equals(path),
                "operator settings",
                "must use an absolute normalized path");
        var directory = new RetainedDirectory(path.getParent());
        directory.checkFile(path);
        ProtocolValues.require(Files.size(path) <= 65536, "operator settings", "exceeds its bound");
        Settings settings = SandcrabProtocol.read(Files.readAllBytes(path), Settings.class);
        ProtocolValues.require(
                Files.isDirectory(settings.metadata(), LinkOption.NOFOLLOW_LINKS),
                "operator metadata",
                "must already exist");
        ProtocolValues.require(
                Files.getAttribute(Path.of("/proc/self"), "unix:uid").equals(settings.appUid()),
                "operator user",
                "must be the configured application UID");
        ProtocolValues.require(
                settings.staging().equals(path.getParent()), "operator staging", "must contain the protected settings");
        return settings;
    }

    private static void verifyArchive(Path path, NativeRepositoryMigration.Archive archive) throws IOException {
        Objects.requireNonNull(archive, "Archive descriptor must be retained");
        new RetainedDirectory(path.getParent()).checkFile(path);
        ProtocolValues.require(Files.size(path) == archive.bytes(), "operator archive", "length changed");
        var digest = SandcrabProtocol.digest();
        long total = 0;
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65536];
            for (int read; (read = input.read(buffer)) >= 0; ) {
                total += read;
                ProtocolValues.require(total <= archive.bytes(), "operator archive", "grew during verification");
                digest.update(buffer, 0, read);
            }
        }
        ProtocolValues.require(
                total == archive.bytes()
                        && HexFormat.of().formatHex(digest.digest()).equals(archive.sha256()),
                "operator archive",
                "must retain its exact source bytes");
    }

    record Settings(
            String operation,
            int appUid,
            Path metadata,
            StoreLimits limits,
            AccountCopyRecord.Owner owner,
            UUID copyId,
            Path socket,
            String profile,
            long volumeBytes,
            long volumeInodes,
            Path staging,
            NativeMigrationControl.Lock sourceLock,
            NativeMigrationControl.Lock deploymentLock) {
        Settings {
            operation = ProtocolValues.uuid(operation, "operator operation");
            ProtocolValues.inRange(appUid, 1, Integer.MAX_VALUE, "operator application UID");
            Objects.requireNonNull(limits, "Operator journal limits must be explicit");
            Objects.requireNonNull(owner, "Operator journal owner must be explicit");
            Objects.requireNonNull(copyId, "Operator copy ID must be explicit");
            Objects.requireNonNull(sourceLock, "Source lock must be inherited");
            Objects.requireNonNull(deploymentLock, "Deployment lock must be inherited");
            for (Path path : new Path[] {metadata, socket, staging}) {
                ProtocolValues.require(
                        path != null && path.isAbsolute() && path.normalize().equals(path),
                        "operator path",
                        "must be absolute and normalized");
            }
            profile = SandcrabProtocol.profile(profile);
            new AccountCopyRecord.NativeStorage(copyId, profile, volumeBytes, volumeInodes);
        }
    }

    record StoreLimits(
            int copies, long recordBytes, long poolBytes, long idleMillis, RetainedBaseline.Limits originalFiles) {
        AccountCopyStore.Limits limits() {
            return new AccountCopyStore.Limits(
                    copies, recordBytes, poolBytes, Duration.ofMillis(idleMillis), originalFiles);
        }
    }
}
