package com.slicercore.calibration;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.SecurityConfig;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.service.QuotePricingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The calibration endpoints sit behind the owner login; the report's JSON shape is what the admin page reads. */
@WebMvcTest(value = CalibrationAdminController.class, properties = {"admin.username=admin", "admin.password=secret"})
@Import({SecurityConfig.class, CalibrationAdminControllerTest.Config.class})
class CalibrationAdminControllerTest {

    private static final String MODEL_ID = "c".repeat(64);
    private static final Instant QUEUED_AT = Instant.parse("2026-10-21T15:00:00Z");

    @TestConfiguration
    static class Config {
        @Bean
        RateLimitProperties rateLimitProperties() {
            return new RateLimitProperties(true, 10, 100, false, "");
        }
    }

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    CalibrationQueue queue;

    @MockitoBean
    CalibrationJob job;

    @MockitoBean
    CalibrationReportService reports;

    @Test
    void withoutCredentialsEverythingIs401() throws Exception {
        mockMvc.perform(get("/api/admin/calibration/report")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/calibration/rows.csv")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/calibration/queue")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/calibration/queue/" + MODEL_ID)).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/calibration/queue/order/POD-1000/1")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/admin/calibration/run-now")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/admin/calibration/report").with(httpBasic("admin", "wrong")))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(queue, job, reports);
    }

    @Test
    void theReportCarriesTheShapeTheAdminPageReads() throws Exception {
        when(reports.report()).thenReturn(sampleReport());

        mockMvc.perform(get("/api/admin/calibration/report").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.models").value(1))
                .andExpect(jsonPath("$.rows").value(5))
                .andExpect(jsonPath("$.sources.quote").value(1))
                .andExpect(jsonPath("$.sources.order").value(0))
                .andExpect(jsonPath("$.sources.manual").value(4))
                .andExpect(jsonPath("$.configured.timeFactor.PETG").value(1.38))
                .andExpect(jsonPath("$.configured.lengthFactor.PCTG").value(0.97))
                .andExpect(jsonPath("$.configured.plateOverheadSeconds").value(300))
                .andExpect(jsonPath("$.timeFactor.PETG.medium.n").value(1))
                .andExpect(jsonPath("$.timeFactor.PETG.medium.mean").value(1.4))
                .andExpect(jsonPath("$.timeFactor.PETG.medium.median").value(1.4))
                .andExpect(jsonPath("$.timeFactor.PETG.medium.min").value(1.4))
                .andExpect(jsonPath("$.timeFactor.PETG.medium.max").value(1.4))
                .andExpect(jsonPath("$.timeFactor.PETG.all.n").value(1))
                .andExpect(jsonPath("$.timeFactor.PETG.coarse.n").value(0))
                .andExpect(jsonPath("$.timeFactor.PETG.coarse.mean").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.timeFactor.PCTG.all.n").value(0))
                .andExpect(jsonPath("$.lengthFactor.PETG.medium.mean").value(0.9))
                .andExpect(jsonPath("$.quantity.4.n").value(1))
                .andExpect(jsonPath("$.quantity.4.perCopyRatio.mean").value(0.944))
                .andExpect(jsonPath("$.quantity.4.plateOverheadSeconds.mean").value(300.0))
                .andExpect(jsonPath("$.quantity.10.n").value(1))
                .andExpect(jsonPath("$.quantity.10.plateOverheadSeconds.n").value(0))
                .andExpect(jsonPath("$.arithmeticError.4.n").value(1))
                .andExpect(jsonPath("$.arithmeticError.4.meanPercent").value(0.0))
                .andExpect(jsonPath("$.arithmeticError.4.meanAbsPercent").value(0.0))
                .andExpect(jsonPath("$.arithmeticError.4.maxAbsPercent").value(0.0))
                .andExpect(jsonPath("$.calibratedModels.length()").value(1))
                .andExpect(jsonPath("$.calibratedModels[0].modelName").value("plate.stl"))
                .andExpect(jsonPath("$.calibratedModels[0].modelSha256").value("b".repeat(64)))
                .andExpect(jsonPath("$.calibratedModels[0].source").value("manual"))
                .andExpect(jsonPath("$.calibratedModels[0].orderNumber").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.calibratedModels[0].measuredAt").value("2026-10-23T03:10:00Z"))
                .andExpect(jsonPath("$.calibratedModels[0].cells").value(4))
                .andExpect(jsonPath("$.calibratedModels[0].triangleCount").value(0))
                .andExpect(jsonPath("$.calibratedModels[0].bbox.widthMm").value(100.0))
                .andExpect(jsonPath("$.calibratedModels[0].bbox.depthMm").value(100.0))
                .andExpect(jsonPath("$.calibratedModels[0].bbox.heightMm").value(20.0));
    }

    @Test
    void theRawRowsComeAsACsvDownload() throws Exception {
        when(reports.rowsCsv()).thenReturn("id,model_sha256\r\n1,abc\r\n");

        mockMvc.perform(get("/api/admin/calibration/rows.csv").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", containsString("text/csv")))
                .andExpect(header().string("Content-Disposition", containsString("slice-calibrations.csv")))
                .andExpect(content().string("id,model_sha256\r\n1,abc\r\n"));
    }

    @Test
    void theQueueListsEntriesWithTheirStatus() throws Exception {
        CalibrationQueueEntry queued = CalibrationQueueEntry.queued("a".repeat(64), "benchy.stl", "k.stl",
                CalibrationSource.ORDER, "POD-1000", QUEUED_AT);
        CalibrationQueueEntry failed = CalibrationQueueEntry.queued("b".repeat(64), "halter.3mf", "h.3mf",
                CalibrationSource.MANUAL, null, QUEUED_AT);
        failed.start(QUEUED_AT.plusSeconds(60));
        failed.finish(QUEUED_AT.plusSeconds(120), 0, "Model file is gone from the calibration directory.");
        when(queue.recent(50)).thenReturn(List.of(failed, queued));

        mockMvc.perform(get("/api/admin/calibration/queue").with(httpBasic("admin", "secret")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].modelName").value("halter.3mf"))
                .andExpect(jsonPath("$[0].source").value("manual"))
                .andExpect(jsonPath("$[0].status").value("failed"))
                .andExpect(jsonPath("$[0].error").value(containsString("gone")))
                .andExpect(jsonPath("$[0].startedAt").value("2026-10-21T15:01:00Z"))
                .andExpect(jsonPath("$[0].finishedAt").value("2026-10-21T15:02:00Z"))
                .andExpect(jsonPath("$[0].cellsWritten").value(0))
                .andExpect(jsonPath("$[1].status").value("queued"))
                .andExpect(jsonPath("$[1].source").value("order"))
                .andExpect(jsonPath("$[1].orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$[1].queuedAt").value("2026-10-21T15:00:00Z"))
                .andExpect(jsonPath("$[1].startedAt").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].error").value(org.hamcrest.Matchers.nullValue()));
        verify(queue).recent(50);
    }

    @Test
    void queueingAStoredModelAnswers202OrA404WhenItExpired() throws Exception {
        when(queue.enqueueStoredModel(MODEL_ID)).thenReturn(CalibrationQueueEntry.queued(MODEL_ID, "Clip.stl",
                MODEL_ID + ".stl", CalibrationSource.MANUAL, null, QUEUED_AT));
        when(queue.enqueueStoredModel("d".repeat(64)))
                .thenThrow(new NoSuchElementException("Model is not in the upload store any more."));

        mockMvc.perform(post("/api/admin/calibration/queue/" + MODEL_ID).with(httpBasic("admin", "secret")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.modelSha256").value(MODEL_ID))
                .andExpect(jsonPath("$.status").value("queued"))
                .andExpect(jsonPath("$.source").value("manual"));
        mockMvc.perform(post("/api/admin/calibration/queue/" + "d".repeat(64)).with(httpBasic("admin", "secret")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value(containsString("upload store")));
    }

    @Test
    void queueingAnOrderItemAnswers202A404OrA409WhenErased() throws Exception {
        when(queue.enqueueOrderItem("POD-1000", 2)).thenReturn(CalibrationQueueEntry.queued("a".repeat(64),
                "halter.3mf", "k.3mf", CalibrationSource.ORDER, "POD-1000", QUEUED_AT));
        when(queue.enqueueOrderItem("POD-1000", 9)).thenThrow(new NoSuchElementException("Order POD-1000 has no item 9."));
        when(queue.enqueueOrderItem("POD-0900", 1))
                .thenThrow(new IllegalStateException("Order POD-0900 was erased; its model files are gone."));

        mockMvc.perform(post("/api/admin/calibration/queue/order/POD-1000/2").with(httpBasic("admin", "secret")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderNumber").value("POD-1000"))
                .andExpect(jsonPath("$.source").value("order"));
        mockMvc.perform(post("/api/admin/calibration/queue/order/POD-1000/9").with(httpBasic("admin", "secret")))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/admin/calibration/queue/order/POD-0900/1").with(httpBasic("admin", "secret")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(containsString("erased")));
    }

    @Test
    void runNowStartsAPassInTheBackgroundAndSaysSoWhenOneAlreadyRuns() throws Exception {
        when(job.runPassAsync()).thenReturn(true, false);
        when(job.isRunning()).thenReturn(true);
        when(queue.waiting()).thenReturn(3L);

        mockMvc.perform(post("/api/admin/calibration/run-now").with(httpBasic("admin", "secret")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.started").value(true))
                .andExpect(jsonPath("$.running").value(true))
                .andExpect(jsonPath("$.queued").value(3));
        mockMvc.perform(post("/api/admin/calibration/run-now").with(httpBasic("admin", "secret")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.started").value(false));
    }

    /** The real report service over one hand-made model, so the JSON is the real serialisation. */
    private static CalibrationReport sampleReport() {
        QuotePricingProperties properties = new QuotePricingProperties("CHF", 5, 4, 3, 16, 1.75, 100, 300,
                new QuotePricingProperties.Plate(256, 256, 10, 15), Map.of(),
                new QuotePricingProperties.Material(Map.of(FilamentType.PETG, 1.38, FilamentType.PCTG, 1.48),
                        Map.of(FilamentType.PETG, 0.97, FilamentType.PCTG, 0.97)));
        CalibrationReportService service = new CalibrationReportService(mock(SliceCalibrationRepository.class),
                new QuotePricingService(properties), properties);
        BoundingBox plate = new BoundingBox(100, 100, 20);
        Instant at = Instant.parse("2026-10-23T03:10:00Z");
        String sha = "b".repeat(64);
        // Oldest first, as the repository returns them: the earlier quote row is superseded by the manual cell.
        return service.report(List.of(
                SliceCalibration.of(sha, "plate.stl", CalibrationSource.QUOTE, null, FilamentType.PLA, PrintQuality.MEDIUM, 1, 1, 4000, 1000, plate, at.minusSeconds(60)),
                SliceCalibration.of(sha, "plate.stl", CalibrationSource.MANUAL, null, FilamentType.PLA, PrintQuality.MEDIUM, 1, 1, 4000, 1000, plate, at),
                SliceCalibration.of(sha, "plate.stl", CalibrationSource.MANUAL, null, FilamentType.PETG, PrintQuality.MEDIUM, 1, 1, 5600, 900, plate, at),
                SliceCalibration.of(sha, "plate.stl", CalibrationSource.MANUAL, null, FilamentType.PLA, PrintQuality.MEDIUM, 4, 1, 15100, 4000, plate, at),
                SliceCalibration.of(sha, "plate.stl", CalibrationSource.MANUAL, null, FilamentType.PLA, PrintQuality.MEDIUM, 10, 3, 37900, 10000, plate, at)));
    }
}
