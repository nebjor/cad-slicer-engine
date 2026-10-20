package com.slicercore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The generated contract is what the frontend builds against. This boots the whole
 * application (Postgres via Testcontainers, skipped without Docker, runs in CI), pins the
 * endpoints and schemas the frontend relies on, and writes the spec to {@code target/openapi.json}
 * so CI can publish it.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OpenApiContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

    @Autowired
    MockMvc mockMvc;

    @Test
    void contractListsTheShopEndpointsAndSchemas() throws Exception {
        String json = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode spec = new ObjectMapper().readTree(json);

        assertThat(spec.path("info").path("title").asText()).isEqualTo("3DBurg shop API");
        assertThat(fieldNames(spec.path("paths"))).contains(
                "/api/quotes", "/api/quotes/options",
                "/api/orders", "/api/orders/settings", "/api/orders/{orderNumber}",
                "/api/payments/mock/{sessionId}/complete",
                "/api/admin/orders", "/api/admin/orders/{orderNumber}/status",
                "/api/admin/orders/{orderNumber}/model", "/api/admin/orders/{orderNumber}/erase");
        assertThat(fieldNames(spec.path("components").path("schemas"))).contains(
                "QuoteResponse", "QuoteOptions", "FilamentOption", "ColorOption", "QualityOption",
                "OrderRequest", "OrderResponse", "OrderSettingsResponse", "OrderTotals", "AdminOrderResponse");
        // Admin endpoints declare the owner login; public ones do not.
        assertThat(spec.path("paths").path("/api/admin/orders").path("get").path("security").toString())
                .contains("adminBasic");
        assertThat(spec.path("paths").path("/api/quotes/options").path("get").has("security")).isFalse();

        Path out = Path.of("target", "openapi.json");
        Files.createDirectories(out.getParent());
        Files.writeString(out, json);
    }

    private static java.util.List<String> fieldNames(JsonNode node) {
        java.util.List<String> names = new java.util.ArrayList<>();
        node.propertyNames().forEach(names::add);
        return names;
    }
}
