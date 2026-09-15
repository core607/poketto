package io.github.core607.poketto.assets.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.junit.jupiter.api.Test;

class PublicImageDownloaderTests {
    @Test
    void rejectsInternalLiteralAndSpecialUseDestinations() throws Exception {
        for (String host : new String[] {
            "127.0.0.1",
            "10.0.0.1",
            "169.254.169.254",
            "100.64.0.1",
            "192.168.0.1",
            "0.0.0.0",
            "198.18.0.1",
            "::1",
            "fc00::1",
            "fe80::1",
            "::ffff:127.0.0.1",
            "2002:7f00:1::1",
            "2001::1"
        }) {
            assertThat(PublicImageDownloader.isPublic(InetAddress.getByName(host)))
                    .as(host)
                    .isFalse();
        }
        assertThat(PublicImageDownloader.isPublic(InetAddress.getByName("1.1.1.1")))
                .isTrue();
        assertThat(PublicImageDownloader.isPublic(InetAddress.getByName("2606:4700:4700::1111")))
                .isTrue();
        try (var downloader = new PublicImageDownloader()) {
            assertThatThrownBy(() -> downloader.download("https://127.0.0.1/private"))
                    .isInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void onlyAllowsDirectHttpsUrlsWithoutAmbientCredentials() {
        for (String url : new String[] {
            "file:///tmp/image.png",
            "http://example.com/x",
            "https://user:pass@example.com/x",
            "https://example.com:8080/x",
            "https://example.com/x#fragment",
            "sandbox:/mnt/data/x.png"
        }) {
            assertThatThrownBy(() -> PublicImageDownloader.validateUri(url))
                    .as(url)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(PublicImageDownloader.validateUri("https://example.com/a.png?signature=abc")
                        .getRawQuery())
                .isEqualTo("signature=abc");
    }
}
