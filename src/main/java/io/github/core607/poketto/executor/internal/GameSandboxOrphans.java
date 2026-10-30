package io.github.core607.poketto.executor.internal;

import java.util.function.Predicate;

/** Only an exclusive game-service owner may discard unowned ephemeral jobs and their receipts. */
final class GameSandboxOrphans {
    private final SandcrabClient client;
    private final Predicate<String> owned;
    private String epoch;
    private String sessionsAfter;
    private String runsAfter;
    private String uploadsAfter;

    GameSandboxOrphans(SandcrabClient client, Predicate<String> owned) {
        this.client = client;
        this.owned = owned;
    }

    void collect(String currentEpoch) {
        if (!currentEpoch.equals(epoch)) {
            epoch = currentEpoch;
            sessionsAfter = null;
            runsAfter = null;
            uploadsAfter = null;
        }
        sessions();
        runs();
        uploads();
    }

    private void sessions() {
        SandcrabProtocol.IdentityPage page = client.sessions(epoch, sessionsAfter);
        String last = null;
        int count = 0;
        for (String id : page.ids()) {
            last = id;
            if (!owned.test(id)) {
                try {
                    var reference = new SandcrabProtocol.Reference(id, epoch);
                    SandcrabProtocol.Session session = client.session(reference);
                    scope(session.profile(), session.volumeId());
                    if (!session.terminal()) {
                        session = client.close(reference);
                    }
                    if (session.terminal()) {
                        client.forget(session);
                    }
                } catch (SandcrabClient.Rejected absent) {
                    requireMissing(absent, "SESSION_NOT_FOUND");
                }
                if (++count == 4) {
                    break;
                }
            }
        }
        sessionsAfter = next(page, last);
    }

    private void runs() {
        SandcrabProtocol.IdentityPage page = client.runs(epoch, runsAfter);
        String last = null;
        int count = 0;
        for (String id : page.ids()) {
            last = id;
            if (!owned.test(id)) {
                try {
                    var reference = new SandcrabProtocol.Reference(id, epoch);
                    SandcrabProtocol.Run run = client.get(reference);
                    scope(run.profile(), null);
                    if (!run.terminal()) {
                        run = client.cancel(reference);
                    }
                    if (run.terminal()) {
                        client.forget(run);
                    }
                } catch (SandcrabClient.Rejected absent) {
                    requireMissing(absent, "RUN_NOT_FOUND");
                }
                if (++count == 4) {
                    break;
                }
            }
        }
        runsAfter = next(page, last);
    }

    private void uploads() {
        SandcrabProtocol.UploadPage page = client.uploads(uploadsAfter);
        String last = null;
        int count = 0;
        for (SandcrabProtocol.Upload upload : page.uploads()) {
            last = upload.id();
            if (!owned.test(upload.id())) {
                try {
                    scope(upload.profile(), upload.volumeId());
                    if (!upload.forgettable()) {
                        upload = client.cancelUpload(upload.reference());
                    }
                    if (upload.forgettable()) {
                        client.forget(upload);
                    }
                } catch (SandcrabClient.Rejected absent) {
                    requireMissing(absent, "UPLOAD_NOT_FOUND");
                }
                if (++count == 4) {
                    break;
                }
            }
        }
        uploadsAfter =
                page.uploads().isEmpty() || last.equals(page.uploads().getLast().id()) && page.nextAfter() == null
                        ? null
                        : last;
    }

    private static String next(SandcrabProtocol.IdentityPage page, String last) {
        return page.ids().isEmpty() || last.equals(page.ids().getLast()) && page.nextAfter() == null ? null : last;
    }

    private static void requireMissing(SandcrabClient.Rejected failure, String code) {
        if (!GameSandboxJob.missing(failure, code)) {
            throw failure;
        }
    }

    private static void scope(String profile, String volumeId) {
        ProtocolValues.require(
                "games".equals(profile) && volumeId == null, "game service", "must own only ephemeral game jobs");
    }
}
