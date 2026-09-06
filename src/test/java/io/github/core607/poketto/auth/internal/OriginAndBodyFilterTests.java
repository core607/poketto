package io.github.core607.poketto.auth.internal;

import static org.assertj.core.api.Assertions.*;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class OriginAndBodyFilterTests {
    private final OriginAndBodyFilter filter = new OriginAndBodyFilter(Set.of());

    @Test
    void unknownBodyReaderAndStreamUseTheSameVerifiedBytes() throws Exception {
        for (boolean reader : new boolean[] {true, false}) {
            var request = unknown("/api/auth/initialize", "猫".repeat(20), "application/json");
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, (wrapped, ignored) -> {
                String text = reader
                        ? wrapped.getReader().readLine()
                        : new String(wrapped.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertThat(text).isEqualTo("猫".repeat(20));
                if (reader) assertThatThrownBy(wrapped::getInputStream).isInstanceOf(IllegalStateException.class);
                else assertThatThrownBy(wrapped::getReader).isInstanceOf(IllegalStateException.class);
            });
        }
    }

    @Test
    void overflowNeverDispatchesAndDoesNotEchoBody() throws Exception {
        var request = unknown("/api/auth/initialize", "x".repeat(16385), "application/json");
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(request, response, (wrapped, ignored) -> called.set(true));
        assertThat(called).isFalse();
        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).doesNotContain("xxx");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
    }

    @Test
    void chunkedFormPreservesQueryPrecedenceRepeatedValuesAndUtf8() throws Exception {
        var request = unknown(
                "/api/auth/login",
                "name=%E7%8C%AB&name=two+words&empty&=value",
                "application/x-www-form-urlencoded; charset=UTF-8");
        request.setQueryString("name=query&only=query-value");
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
            var form = (HttpServletRequest) wrapped;
            assertThat(form.getParameter("name")).isEqualTo("query");
            assertThat(form.getParameterValues("name")).containsExactly("query", "猫", "two words");
            assertThat(form.getParameter("empty")).isEmpty();
            assertThat(form.getParameter("only")).isEqualTo("query-value");
            assertThat(form.getParameter("")).isEqualTo("value");
            assertThat(form.getParameter("absent")).isNull();
            assertThat(form.getParameterNames().asIterator()).toIterable().contains("name", "empty", "only", "");
            form.getParameterMap().get("name")[0] = "changed";
            assertThat(form.getParameter("name")).isEqualTo("query");
        });
    }

    @Test
    void malformedChunkedFormIsRejectedBeforeDispatch() throws Exception {
        var response = new MockHttpServletResponse();
        var called = new AtomicBoolean();
        filter.doFilter(
                unknown("/api/auth/login", "name=%xx", "application/x-www-form-urlencoded"),
                response,
                (wrapped, ignored) -> called.set(true));
        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(called).isFalse();
    }

    @Test
    void multipartAssetKeepsTheOriginalUnconsumedRequestForServletPartParsing() throws Exception {
        var request =
                unknown("/api/admin/assets", "--boundary\r\nfile bytes\r\n", "multipart/form-data; boundary=boundary");
        filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
            assertThat(wrapped).isSameAs(request);
            assertThat(wrapped.getInputStream().readAllBytes()).isEqualTo(request.getContentAsByteArray());
        });
    }

    @Test
    void administrationBodiesRemainUnconsumedForTheIdentityAndAdmissionFilters() throws Exception {
        for (String path :
                new String[] {"/api/admin/repository/patch", "/api/admin/repository/preview", "/api/admin/assets"}) {
            var called = new AtomicBoolean();
            var request = new MockHttpServletRequest("POST", path) {
                @Override
                public long getContentLengthLong() {
                    return -1;
                }

                @Override
                public jakarta.servlet.ServletInputStream getInputStream() {
                    throw new AssertionError("administration body read before identity and admission");
                }
            };
            request.setContentType("application/json");
            filter.doFilter(request, new MockHttpServletResponse(), (wrapped, ignored) -> {
                assertThat(wrapped).isSameAs(request);
                called.set(true);
            });
            assertThat(called).isTrue();
        }
    }

    private MockHttpServletRequest unknown(String path, String body, String contentType) {
        var request = new MockHttpServletRequest("POST", path) {
            @Override
            public long getContentLengthLong() {
                return -1;
            }

            @Override
            public int getContentLength() {
                return -1;
            }
        };
        request.setCharacterEncoding(StandardCharsets.UTF_8.name());
        request.setContentType(contentType);
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}
