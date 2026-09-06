package io.github.core607.poketto.acceptance;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

/** Explicitly loaded only by the proxy gate; the real security chain protects this servlet. */
@TestConfiguration(proxyBeanMethods = false)
public class ProxyRequestProbeConfiguration {
    @Bean
    ServletRegistrationBean<HttpServlet> forwardingProbe() {
        return new ServletRegistrationBean<>(
                new HttpServlet() {
                    @Override
                    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
                        response.setContentType("text/plain");
                        response.getWriter()
                                .printf(
                                        "%s\n%d\n%s\n",
                                        request.getRemoteAddr(), request.getServerPort(), request.isSecure());
                    }
                },
                "/api/auth/forwarding-probe");
    }
}
