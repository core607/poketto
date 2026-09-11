package io.github.core607.poketto.content.internal;

import static org.assertj.core.api.Assertions.*;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.Test;

class ManagedGitHttpTests {
    @Test
    void rejectsRedirectOriginsOtherRepositoriesAndNonGitTargets() throws Exception {
        URI repository = URI.create("https://github.com/owner/repo.git");
        ManagedGitHttp.validate(
                repository,
                URI.create(repository + "/info/refs?service=git-upload-pack").toURL());
        ManagedGitHttp.validate(
                repository, URI.create(repository + "/git-receive-pack").toURL());
        for (String target : List.of(
                "http://github.com/owner/repo.git/info/refs",
                "https://127.0.0.1/owner/repo.git/info/refs",
                "https://github.com:8080/owner/repo.git/info/refs",
                "https://github.com/owner/other.git/info/refs",
                "https://github.com/owner/repo.git/../info/refs",
                "https://github.com/owner/repo.git/info/refs?redirect=x",
                "https://token@github.com/owner/repo.git/info/refs")) {
            assertThatThrownBy(() -> ManagedGitHttp.validate(
                            repository, URI.create(target).toURL()))
                    .isInstanceOf(java.io.IOException.class);
        }
        for (String address : List.of(
                "127.0.0.1",
                "10.0.0.1",
                "172.16.0.1",
                "192.168.0.1",
                "169.254.169.254",
                "100.64.1.1",
                "198.18.0.1",
                "0.1.2.3",
                "224.0.0.1",
                "::1",
                "fc00::1",
                "fe80::1",
                "2002:7f00:1::")) {
            assertThat(PublicNetworkDestination.isPublic(InetAddress.getByName(address)))
                    .as(address)
                    .isFalse();
        }
        assertThat(PublicNetworkDestination.isPublic(InetAddress.getByName("140.82.114.4")))
                .isTrue();
        assertThat(PublicNetworkDestination.isPublic(InetAddress.getByName("2606:4700::1111")))
                .isTrue();
    }
}
