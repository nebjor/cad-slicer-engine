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
                "/api/quotes", "/api/quotes/options", "/api/models",
                "/api/orders", "/api/orders/settings", "/api/orders/{orderNumber}",
                "/api/payments/mock/{sessionId}/complete",
                "/api/account", "/api/account/login-links", "/api/account/sessions", "/api/account/orders",
                "/api/admin/orders", "/api/admin/orders/{orderNumber}/status",
                "/api/admin/orders/{orderNumber}/items/{position}/model", "/api/admin/orders/{orderNumber}/erase",
                "/api/admin/calibration/report", "/api/admin/calibration/rows.csv", "/api/admin/calibration/rows",
                "/api/admin/calibration/queue",
                "/api/admin/calibration/queue/{modelId}", "/api/admin/calibration/queue/order/{orderNumber}/{position}",
                "/api/admin/calibration/run-now");
        assertThat(fieldNames(spec.path("paths"))).doesNotContain("/api/admin/orders/{orderNumber}/model");
        assertThat(fieldNames(spec.path("components").path("schemas"))).contains(
                "QuoteResponse", "QuoteOptions", "FilamentOption", "ColorOption", "QualityOption", "UploadedModel",
                "OrderRequest", "CustomerDetails", "OrderItemRequest", "OrderResponse", "OrderItemResponse",
                "OrderSettingsResponse", "OrderTotals", "AdminOrderResponse", "AdminOrderItemResponse",
                "AccountResponse", "LoginLinkRequest", "SessionRequest", "AccountUpdateRequest",
                "CalibrationReport", "CalibrationStats", "QuantityStats", "ArithmeticError", "CalibratedModel",
                "CalibrationQueueResponse", "SliceCalibrationRow");
        // Account mutations take JSON only; that, with SameSite=Lax, is the CSRF guard (ADR 0005).
        assertThat(spec.path("paths").path("/api/account/login-links").path("post").path("requestBody").path("content")
                .has("application/json")).isTrue();
        assertThat(spec.path("paths").path("/api/account").path("get").has("security")).isFalse();
        // Orders are placed as JSON, not multipart.
        assertThat(spec.path("paths").path("/api/orders").path("post").path("requestBody").path("content")
                .has("application/json")).isTrue();
        // Admin endpoints declare the owner login; public ones do not.
        assertThat(spec.path("paths").path("/api/admin/orders").path("get").path("security").toString())
                .contains("adminBasic");
        assertThat(spec.path("paths").path("/api/admin/calibration/report").path("get").path("security").toString())
                .contains("adminBasic");
        assertThat(spec.path("paths").path("/api/admin/calibration/rows.csv").path("get").path("responses").path("200")
                .path("content").has("text/csv")).isTrue();
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
