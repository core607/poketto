package io.github.core607.poketto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.autoconfigure.ServerProperties;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "poketto.workspace.catalog.enabled=false",
            "spring.autoconfigure.exclude=" + "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration"
        })
@AutoConfigureMockMvc
class PokettoApplicationTests {

    @TempDir
    static Path dataDirectory;

    @DynamicPropertySource
    static void contentProperties(DynamicPropertyRegistry registry) {
        registry.add("poketto.data-dir", () -> dataDirectory.toAbsolutePath().toString());
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ServerProperties serverProperties;

    @Value("${local.server.port}")
    private int serverPort;

    @MockitoSpyBean(name = "executorProtocolHealthIndicator")
    private HealthIndicator executorHealth;

    @Test
    void directEntryDoesNotEnableForwardedHeadersByDefault() {
        assertEquals(ServerProperties.ForwardHeadersStrategy.NONE, serverProperties.getForwardHeadersStrategy());
    }

    @Test
    void contextLoads() {}

    @Test
    void exposesHealthAndProbesWithoutDetails() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    void deploymentCanReadExecutorHealthOverHttpAndProtocolFailureStopsReadiness() throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create("http://127.0.0.1:" + serverPort + "/actuator/health/executor"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> healthy = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, healthy.statusCode());
            assertEquals("{\"status\":\"UP\"}", healthy.body());
            doReturn(Health.down().build()).when(executorHealth).health();
            HttpResponse<String> failed = client.send(request, HttpResponse.BodyHandlers.ofString());
            assertEquals(503, failed.statusCode());
            assertEquals("{\"status\":\"DOWN\"}", failed.body());
            mvc.perform(get("/actuator/health/readiness")).andExpect(status().isServiceUnavailable());
            mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        }
    }

    @Test
    void exposesNoOtherManagementEndpoint() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
        // Metrics stay off unless POKETTO_METRICS_ENABLED turns the export on.
        mvc.perform(get("/actuator/prometheus")).andExpect(status().isNotFound());
    }
}
