package com.slicercore.controller;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.ModelNotFoundException;
import com.slicercore.model.RateLimitExceededException;
import com.slicercore.model.SlicerBusyException;
import com.slicercore.model.SlicingException;
import com.slicercore.service.FilamentCatalog;
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

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract only: parameter parsing, status codes, problem details and headers. */
@WebMvcTest(QuoteController.class)
@Import({QuoteControllerTest.Config.class, FilamentCatalog.class, SecurityConfig.class})
class QuoteControllerTest {

    // Priced explicitly so the catalogue hands them back unchanged and the stubs match.
    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", 36.0);
    private static final ColorOption WHITE = new ColorOption("white", "White", "#f2f2f2", 36.0);
    private static final ColorOption RED = new ColorOption("red", "Red", "#d62828", 45.0);

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "help@example.com");
        }

        @Bean
        QuotePricingProperties quotePricingProperties() {
            return new QuotePricingProperties("CHF", 5, 4, 3, 16, 1.75, 100, 300, new QuotePricingProperties.Plate(256, 256, 10, 15),
                    Map.of(FilamentType.PLA, 36.0, FilamentType.PETG, 45.0), QuotePricingProperties.Material.UNSCALED);
        }

        @Bean
        FilamentCatalogProperties filamentCatalogProperties() {
            return new FilamentCatalogProperties(Map.of(
                    FilamentType.PLA, List.of(BLACK, WHITE, RED),
                    FilamentType.PETG, List.of(BLACK, WHITE)));
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    QuoteService quoteService;

    private final MockMultipartFile benchy = new MockMultipartFile(
            "file", "benchy.stl", "application/octet-stream", "solid benchy endsolid benchy".getBytes());

    @Test
    void uploadReturnsTheQuoteAsJson() throws Exception {
        when(quoteService.quoteUpload(any(), eq(FilamentType.PETG), eq(WHITE), eq(PrintQuality.HIGH), eq(3), anyString()))
                .thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").file(benchy)
                        .param("filament", "petg").param("color", "white")
                        .param("quality", "high").param("quantity", "3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("benchy.stl"))
                .andExpect(jsonPath("$.color.code").value("white"))
                .andExpect(jsonPath("$.color.hex").value("#f2f2f2"))
                .andExpect(jsonPath("$.totalPriceChf").value(23.6))
                .andExpect(jsonPath("$.plateCount").value(1))
                .andExpect(jsonPath("$.boundingBoxMm.widthMm").value(60.0))
                .andExpect(jsonPath("$.warnings[0]").value("Object benchy.stl has thin walls"))
                .andExpect(jsonPath("$.manifold").value(false));
    }

    @Test
    void uploadDefaultsToPlaMediumSingleInTheFirstColour() throws Exception {
        when(quoteService.quoteUpload(any(), eq(FilamentType.PLA), eq(BLACK), eq(PrintQuality.MEDIUM), eq(1), anyString()))
                .thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").file(benchy)).andExpect(status().isOk());
    }

    @Test
    void aKeptModelIsQuotedByIdWithoutAFile() throws Exception {
        String modelId = "a".repeat(64);
        when(quoteService.quoteStoredModel(eq(modelId), eq(FilamentType.PLA), eq(BLACK), eq(PrintQuality.MEDIUM), eq(2), anyString()))
                .thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").param("modelId", modelId).param("quantity", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fileName").value("benchy.stl"));

        verify(quoteService, never()).quoteUpload(any(), any(), any(), any(), anyInt(), anyString());
    }

    @Test
    void anExpiredModelIdIs404WithACodeTheClientActsOn() throws Exception {
        when(quoteService.quoteStoredModel(anyString(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new ModelNotFoundException("a".repeat(64)));

        mockMvc.perform(multipart("/api/quotes").param("modelId", "a".repeat(64)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("model-expired"))
                .andExpect(jsonPath("$.detail").value(containsString("upload the model again")));
    }

    @Test
    void unsupportedFilamentIsRejectedBeforeTheService() throws Exception {
        mockMvc.perform(multipart("/api/quotes").file(benchy).param("filament", "ABS"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("Unsupported filament 'ABS'")));

        verifyNoInteractions(quoteService);
    }

    @Test
    void colourNotStockedForTheFilamentIsRejected() throws Exception {
        mockMvc.perform(multipart("/api/quotes").file(benchy).param("filament", "PETG").param("color", "red"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("'red' is not available for PETG")));

        verifyNoInteractions(quoteService);
    }

    @Test
    void badInputFromTheServiceIs400() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("Quantity must be between 1 and 100."));

        mockMvc.perform(multipart("/api/quotes").file(benchy).param("quantity", "99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Quantity must be between 1 and 100."));
    }

    @Test
    void oversizedUploadIs413() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new MaxUploadSizeExceededException(50L * 1024 * 1024));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.detail").value(containsString("size limit")));
    }

    @Test
    void slicerFailureIs422WithoutServerPaths() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new SlicingException("Slicer CLI failed with exit code 254."));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.detail").value("Slicer CLI failed with exit code 254."));
    }

    @Test
    void rateLimitIs429WithRetryAfterAndSupportEmail() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new RateLimitExceededException("Rate limit reached.", 42));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "42"))
                .andExpect(jsonPath("$.retryAfterSeconds").value(42))
                .andExpect(jsonPath("$.supportEmail").value("help@example.com"));
    }

    @Test
    void busySlicerIs503WithRetryAfter() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), any(), anyInt(), anyString()))
                .thenThrow(new SlicerBusyException("The slicer is busy right now.", 20));

        mockMvc.perform(multipart("/api/quotes").file(benchy))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "20"));
    }

    @Test
    void clientAddressIsPassedAsRateLimitKey() throws Exception {
        when(quoteService.quoteUpload(any(), any(), any(), any(), anyInt(), anyString())).thenReturn(sampleQuote());

        mockMvc.perform(multipart("/api/quotes").file(benchy)
                        .with(request -> {
                            request.setRemoteAddr("203.0.113.7");
                            return request;
                        })
                        .header("X-Forwarded-For", "198.51.100.1"))
                .andExpect(status().isOk());

        // Forwarded headers are ignored unless the operator opted in.
        verify(quoteService).quoteUpload(any(), any(), any(), any(), anyInt(), eq("203.0.113.7"));
    }

    @Test
    void optionsListWhatTheBackendAccepts() throws Exception {
        when(quoteService.maxQuantity()).thenReturn(100);

        mockMvc.perform(get("/api/quotes/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.filaments[*].code").value(contains("PLA", "PETG", "PCTG")))
                .andExpect(jsonPath("$.filaments[2].label").value("PCTG (transparent)"))
                .andExpect(jsonPath("$.filaments[0].colors[*].code").value(contains("black", "white", "red")))
                .andExpect(jsonPath("$.filaments[1].colors[*].code").value(contains("black", "white")))
                .andExpect(jsonPath("$.filaments[0].colors[2].hex").value("#d62828"))
                .andExpect(jsonPath("$.qualities[*].code").value(contains("coarse", "medium", "high")))
                .andExpect(jsonPath("$.qualities[1].label").value("Standard"))
                .andExpect(jsonPath("$.qualities[1].profileName").value("0.20mm Standard @BBL A1"))
                .andExpect(jsonPath("$.qualities[1].layerHeightMm").value(0.2))
                .andExpect(jsonPath("$.maxQuantity").value(100));
    }

    @Test
    void publicEndpointsNeedNoLogin() throws Exception {
        when(quoteService.maxQuantity()).thenReturn(100);

        mockMvc.perform(get("/api/quotes/options")).andExpect(status().isOk());
    }

    private static QuoteResponse sampleQuote() {
        return new QuoteResponse("benchy.stl", "PETG", WHITE, "high", 3, "Bambu Lab A1 0.4 nozzle", 0.12, 1,
                2933, 8799, 10710, 10.9, 32.7, new BoundingBox(60.0, 31.0, 48.0), 225154,
                9.8, 1.4, 5.0, 3.7, 23.6, "CHF", 4, List.of("Object benchy.stl has thin walls"), false);
    }
}
