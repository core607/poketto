package io.github.core607.poketto;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
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
    void exposesNoOtherManagementEndpoint() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isNotFound());
        mvc.perform(get("/actuator/beans")).andExpect(status().isNotFound());
    }
}
