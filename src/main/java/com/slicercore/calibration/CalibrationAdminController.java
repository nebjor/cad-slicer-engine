package com.slicercore.calibration;

import com.slicercore.config.OpenApiConfig;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/**
 * The owner's view of the slice calibration (ADR 0008), behind the admin login.
 *
 * GET  /api/admin/calibration/report                          factors, overhead and arithmetic error over all models
 * GET  /api/admin/calibration/rows.csv                        every raw row
 * GET  /api/admin/calibration/queue?limit=50                  queued, running and finished models, newest first
 * POST /api/admin/calibration/queue/{modelId}                 queue a model from the upload store; 202
 * POST /api/admin/calibration/queue/order/{number}/{position} queue an order item; 202, 409 when erased
 * POST /api/admin/calibration/run-now                         one pass in the background; 202
 */
@RestController
@RequestMapping("/api/admin/calibration")
@Tag(name = "Admin calibration", description = "Slice calibration from real orders; HTTP Basic with the admin credentials")
@SecurityRequirement(name = OpenApiConfig.ADMIN_AUTH)
public class CalibrationAdminController {

    private static final int DEFAULT_QUEUE_LIMIT = 50;

    private final CalibrationQueue queue;
    private final CalibrationJob job;
    private final CalibrationReportService reports;

    public CalibrationAdminController(CalibrationQueue queue, CalibrationJob job, CalibrationReportService reports) {
        this.queue = queue;
        this.job = job;
        this.reports = reports;
    }

    @GetMapping("/report")
    public CalibrationReport report() {
        return reports.report();
    }

    @GetMapping(value = "/rows.csv", produces = "text/csv")
    public ResponseEntity<String> rowsCsv() {
        ContentDisposition disposition = ContentDisposition.attachment().filename("slice-calibrations.csv").build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv"))
                .body(reports.rowsCsv());
    }

    @GetMapping("/queue")
    public List<CalibrationQueueResponse> queue(@RequestParam(defaultValue = "" + DEFAULT_QUEUE_LIMIT) int limit) {
        return queue.recent(limit).stream().map(CalibrationQueueResponse::from).toList();
    }

    @PostMapping("/queue/{modelId}")
    public ResponseEntity<CalibrationQueueResponse> queueStoredModel(@PathVariable String modelId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CalibrationQueueResponse.from(queue.enqueueStoredModel(modelId)));
    }

    @PostMapping("/queue/order/{orderNumber}/{position}")
    public ResponseEntity<CalibrationQueueResponse> queueOrderItem(@PathVariable String orderNumber,
                                                                   @PathVariable int position) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(CalibrationQueueResponse.from(queue.enqueueOrderItem(orderNumber, position)));
    }

    /** Starts one pass now; {@code started} is false when a pass is already running. */
    @PostMapping("/run-now")
    public ResponseEntity<Map<String, Object>> runNow() {
        boolean started = job.runPassAsync();
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(Map.of("started", started, "running", job.isRunning(), "queued", queue.waiting()));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ProblemDetail handleNotFound(NoSuchElementException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail handleConflict(IllegalStateException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(UncheckedIOException.class)
    public ProblemDetail handleStorageFailure(UncheckedIOException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
}
