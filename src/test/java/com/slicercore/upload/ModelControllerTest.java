package com.slicercore.upload;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
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
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract of the one-time upload: status codes and the id the client quotes with. */
@WebMvcTest(ModelController.class)
@Import({ModelControllerTest.Config.class, SecurityConfig.class})
class ModelControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "");
        }
    }

    private final MockMultipartFile benchy = new MockMultipartFile("file", "benchy.stl",
            "application/octet-stream", "solid benchy endsolid benchy".getBytes());

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    UploadService uploadService;

    @Test
    void uploadKeepsTheModelAndReturnsTheIdToQuoteWith() throws Exception {
        when(uploadService.storeUpload(any())).thenReturn(new UploadedModel("a".repeat(64), "benchy.stl", 28));

        mockMvc.perform(multipart("/api/models").file(benchy))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.modelId").value("a".repeat(64)))
                .andExpect(jsonPath("$.fileName").value("benchy.stl"))
                .andExpect(jsonPath("$.sizeBytes").value(28));
    }

    @Test
    void unsupportedFilesAre400() throws Exception {
        when(uploadService.storeUpload(any())).thenThrow(new IllegalArgumentException("Only .stl and .3mf files are supported."));

        mockMvc.perform(multipart("/api/models").file(benchy))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString(".stl and .3mf")));
    }

    @Test
    void oversizedUploadsAre413() throws Exception {
        when(uploadService.storeUpload(any())).thenThrow(new MaxUploadSizeExceededException(50L * 1024 * 1024));

        mockMvc.perform(multipart("/api/models").file(benchy))
                .andExpect(status().isPayloadTooLarge());
    }
}
