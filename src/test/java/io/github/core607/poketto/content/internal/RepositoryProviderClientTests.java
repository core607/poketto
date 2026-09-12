package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.core607.poketto.content.RepositoryConnectionException;
import io.github.core607.poketto.content.RepositoryCoordinates;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class RepositoryProviderClientTests {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void acceptsProviderIdentityAndRejectsReadOnlyRenamedOrArchivedRepositories() {
        var github = RepositoryCoordinates.parse("https://github.com/owner/repo");
        String metadata = """
                {"id":123,"full_name":"Owner/Repo","private":true,"archived":false,
                 "disabled":false,"permissions":{"push":true}}
                """;
        var result = RepositoryProviderClient.parse(github, json.readTree(metadata));
        assertThat(result.identity()).isEqualTo("github:123");
        assertThat(result.privateRepository()).isTrue();
        for (String invalid : List.of(
                metadata.replace("\"push\":true", "\"push\":false"),
                metadata.replace("Owner/Repo", "Another/Repo"),
                metadata.replace("\"archived\":false", "\"archived\":true"))) {
            assertThatThrownBy(() -> RepositoryProviderClient.parse(github, json.readTree(invalid)))
                    .isInstanceOf(RepositoryConnectionException.class);
        }
        var cnb = RepositoryCoordinates.parse("https://cnb.cool/group/repo");
        String cnbMetadata = """
                {"id":"987","path":"group/repo","visibility_level":"Private","status":0,"freeze":false,"access":"Developer"}
                """;
        assertThat(RepositoryProviderClient.parse(cnb, json.readTree(cnbMetadata))
                        .identity())
                .isEqualTo("cnb:987");
        for (String invalid : List.of(
                cnbMetadata.replace("Developer", "Reporter"),
                cnbMetadata.replace("\"status\":0", "\"status\":1"),
                "{}")) {
            assertThatThrownBy(() -> RepositoryProviderClient.parse(cnb, json.readTree(invalid)))
                    .isInstanceOf(RepositoryConnectionException.class);
        }
    }

    @Test
    void boundsCompleteProviderResponseBeforeCopyingExcessBytes() {
        var exact = new RepositoryProviderClient.BoundedBody(4);
        var exactSubscription = new Subscription();
        exact.onSubscribe(exactSubscription);
        exact.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4})));
        exact.onComplete();
        assertThat(exact.getBody().toCompletableFuture().join()).containsExactly(1, 2, 3, 4);
        var overflow = new RepositoryProviderClient.BoundedBody(4);
        var subscription = new Subscription();
        overflow.onSubscribe(subscription);
        overflow.onNext(List.of(ByteBuffer.wrap(new byte[] {1, 2}), ByteBuffer.wrap(new byte[] {3, 4, 5})));
        assertThat(subscription.cancelled).isTrue();
        assertThat(overflow.getBody().toCompletableFuture()).isCompletedExceptionally();
    }

    private static final class Subscription implements Flow.Subscription {
        boolean cancelled;

        public void request(long count) {}

        public void cancel() {
            cancelled = true;
        }
    }
}
