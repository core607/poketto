package io.github.core607.poketto.executor.internal;

import java.io.IOException;
import java.security.DigestInputStream;
import java.time.Duration;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;

/** Authenticated public template calls. The application separately journals ownership of shared bases. */
final class SandcrabTemplates {
    private final SandcrabClient client;
    private final SandboxHttp http;

    SandcrabTemplates(SandcrabClient client, SandboxHttp http) {
        this.client = client;
        this.http = http;
    }

    SandcrabProtocol.Template publish(String profile, NativeTransferStore.File archive, Runnable authorize) {
        authorize.run();
        var digest = SandcrabProtocol.digest();
        try (var file = archive.open();
                var input = new DigestInputStream(new SandcrabClient.CheckedSource(file, authorize), digest)) {
            var template = http.stream(
                    "POST",
                    "/v1/templates?profile=" + SandcrabProtocol.profile(profile),
                    input,
                    archive.size(),
                    Duration.ofSeconds(90),
                    (head, response) ->
                            SandcrabClient.readResponse(head, response, SandcrabProtocol.Template.class, 200));
            require(
                    HexFormat.of().formatHex(digest.digest()).equals(archive.sha256()),
                    "Staged template bytes changed during publication");
            authorize.run();
            return template;
        } catch (IOException failure) {
            throw new WorkerUnavailableException(failure);
        }
    }

    SandcrabProtocol.Template get(String id) {
        var value = client.request(
                "GET",
                "/v1/templates/" + ProtocolValues.hex(id, 64, "template ID"),
                null,
                SandcrabProtocol.Template.class,
                200);
        require(value.id().equals(id), "Template lookup changed its identity");
        return value;
    }

    Set<String> ids() {
        var result = new HashSet<String>();
        String after = null;
        do {
            String path = "/v1/templates" + (after == null ? "" : "?after=" + after);
            var page = client.request("GET", path, null, SandcrabProtocol.TemplatePage.class, 200);
            String previous = after == null ? "" : after;
            for (var template : page.templates()) {
                require(
                        template.id().compareTo(previous) > 0 && result.add(template.id()) && result.size() <= 1024,
                        "Invalid template inventory");
                previous = template.id();
            }
            after = page.nextAfter();
        } while (after != null);
        return Set.copyOf(result);
    }

    void delete(String id) {
        client.request(
                "DELETE",
                "/v1/templates/" + ProtocolValues.hex(id, 64, "template ID"),
                null,
                SandcrabProtocol.Deleted.class,
                200);
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new WorkerUnavailableException(new IOException(message));
        }
    }
}
