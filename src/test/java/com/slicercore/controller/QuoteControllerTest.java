package com.slicercore.controller;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.RateLimitExceededException;
import com.slicercore.model.SlicerBusyException;
import com.slicercore.model.SlicingException;
import com.slicercore.service.QuoteRateLimiter;
import com.slicercore.service.QuoteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract only: parameter parsing, status codes, problem details and headers. */
@WebMvcTest({QuoteController.class, AdminController.class})
@Import(QuoteControllerTest.Config.class)
class QuoteControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "help@example.com", "secret");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    QuoteService quoteService;

    @MockitoBean
    QuoteRateLimiter rateLimiter;

    private final MockMultipartFile benchy = new MockMultipartFile(
            "file", "benchy.stl", "application/octet-stream", "solid benchy endsolid benchy".getBytes());

    @Test
    void uploadReturnsTheQuoteAsJson() throws Exception {
        when(quoteService.quoteUpload(any(), eq(FilamentType.PETG), eq(PrintQuality.HIGH), eq(3), anyString()))
                .thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").file(benchy)
                        .param("filament", "petg").param("quality", "high").param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("benchy.stl"))
                .andExpect(jsonPath("$.totalPriceChf").value(23.6))
                .andExpect(jsonPath("$.plateCount").value(1))
                .andExpect(jsonPath("$.boundingBoxMm.widthMm").value(60.0));
    }

    @Test
    void uploadDefaultsToPlaMediumSingle() throws Exception {
        when(quoteService.quoteUpload(any(), eq(FilamentType.PLA), eq(PrintQuality.MEDIUM), eq(1), anyString()))
                .thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").file(benchy)).andExpect(status().isOk());
    }

    @Test
    void unsupportedFilamentIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(multipart("/api/quotes").file(benchy).param("filament", "ABS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Unsupported filament 'ABS'")));

        verifyNoInteractions(quoteService);
    }

    @Test
    void badInputFromTheServiceIs400() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("Quantity must be between 1 and 20."));

        mockMvc.perform(multipart("/api/quotes").file(benchy).param("quantity", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Quantity must be between 1 and 20."));
    }

    @Test
    void oversizedUploadIs413() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new MaxUploadSizeExceededException(50L * 1024 * 1024));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value(containsString("size limit")));
    }

    @Test
    void slicerFailureIs422WithoutServerPaths() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new SlicingException("Slicer CLI failed with exit code 254."));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Slicer CLI failed with exit code 254."));
    }

    @Test
    void rateLimitIs429WithRetryAfterAndSupportEmail() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new RateLimitExceededException("Rate limit reached.", 42));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(42))
                .andExpect(jsonPath("$.supportEmail").value("help@example.com"));
    }

    @Test
    void busySlicerIs503WithRetryAfter() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new SlicerBusyException("The slicer is busy right now.", 20));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "20"));
    }

    @Test
    void clientAddressIsPassedAsRateLimitKey() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), anyInt(), anyString())).thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").file(benchy)
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.1"))
                .andExpect(status().isOk());

        // Forwarded headers are ignored unless the operator opted in.
        verify(quoteService).quoteUpload(any(), any(), any(), anyInt(), eq("203.0.113.7"));
    }

    @Test
    void sampleEndpointRefusesPathTraversal() throws Exception {
        mockMvc.perform(get("/api/quotes/sample").param("fileName", "../../etc/passwd"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/quotes/sample").param("fileName", "does-not-exist.stl"))
                .andExpect(status().isNotFound());

        verifyNoInteractions(quoteService);
    }

    @Test
    void optionsListWhatTheBackendAccepts() throws Exception {
        when(quoteService.maxQuantity()).thenReturn(20);

        mockMvc.perform(get("/api/quotes/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filaments[*].code").value(org.hamcrest.Matchers.contains("PLA", "PETG")))
                .andExpect(jsonPath("$.qualities[1].layerHeightMm").value(0.2))
                .andExpect(jsonPath("$.maxQuantity").value(20));
    }

    @Test
    void adminResetRequiresTheConfiguredToken() throws Exception {
        mockMvc.perform(post("/api/admin/rate-limits/reset")).andExpect(status().isForbidden());
        mockMvc.perform(post("/api/admin/rate-limits/reset").header("X-Admin-Token", "wrong"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(rateLimiter);

        when(rateLimiter.reset("203.0.113.7")).thenReturn(true);
        mockMvc.perform(post("/api/admin/rate-limits/reset")
                        .header("X-Admin-Token", "secret").param("clientKey", "203.0.113.7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value("203.0.113.7"))
                .andExpect(jsonPath("$.hadUsage").value(true));

        mockMvc.perform(post("/api/admin/rate-limits/reset").header("X-Admin-Token", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reset").value("all"));
        verify(rateLimiter).resetAll();
    }

    private static QuoteResponse sampleQuote() {
        return new QuoteResponse("benchy.stl", "PETG", "high", 3, "Bambu Lab A1 0.4 nozzle", 0.12, 1,
                2933, 8799, 10710, 10.9, 32.7, new BoundingBox(60.0, 31.0, 48.0), 225154,
                9.8, 1.4, 5.0, 3.7, 16.1 + 7.5, "CHF", 4);
    }
}
