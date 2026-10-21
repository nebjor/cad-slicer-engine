package com.slicercore.controller;

import com.slicercore.config.RateLimitProperties;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteOptions;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.ColorOption;
import com.slicercore.model.ModelNotFoundException;
import com.slicercore.model.RateLimitExceededException;
import com.slicercore.model.SlicerBusyException;
import com.slicercore.model.SlicingException;
import com.slicercore.service.FilamentCatalog;
import com.slicercore.service.QuoteService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Quote API.
 *
 * POST /api/quotes            multipart upload of an STL/3MF, or the modelId of an upload kept by
 *                             POST /api/models, plus filament, quality, quantity
 * GET  /api/quotes/sample     quote one of the bundled sample models (local development)
 * GET  /api/quotes/options    filaments, qualities and limits the backend accepts
 *
 * Failures are reported as RFC 9457 problem details: 400 for bad input, 404 with code
 * "model-expired" for an unknown modelId, 413 for oversized uploads, 422 when the slicer could
 * not process the model, 429 when the client exceeded its rate limit and 503 when no slicer
 * slot is free. The last two carry a Retry-After header.
 */
@RestController
@RequestMapping("/api/quotes")
@Tag(name = "Quotes", description = "Slice an uploaded model and price it")
public class QuoteController {

    private static final Logger log = LoggerFactory.getLogger(QuoteController.class);

    private final QuoteService quoteService;
    private final FilamentCatalog catalog;
    private final RateLimitProperties rateLimitProperties;
    private final Path sampleModelsDir;

    public QuoteController(QuoteService quoteService,
                           FilamentCatalog catalog,
                           RateLimitProperties rateLimitProperties,
                           @Value("${sample-models-dir:/workspace/public}") String sampleModelsDir) {
        this.quoteService = quoteService;
        this.catalog = catalog;
        this.rateLimitProperties = rateLimitProperties;
        this.sampleModelsDir = Path.of(sampleModelsDir).toAbsolutePath().normalize();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuoteResponse quoteUpload(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String modelId,
            @RequestParam(defaultValue = "PLA") String filament,
            @RequestParam(required = false) String color,
            @RequestParam(defaultValue = "medium") String quality,
            @RequestParam(defaultValue = "1") int quantity,
            HttpServletRequest request) {
        FilamentType filamentType = FilamentType.fromInput(filament);
        ColorOption colorOption = catalog.resolve(filamentType, color);
        PrintQuality printQuality = PrintQuality.fromInput(quality);
        if (modelId != null && !modelId.isBlank()) {
            return quoteService.quoteStoredModel(modelId.trim(), filamentType, colorOption, printQuality, quantity,
                    rateLimitProperties.clientKey(request));
        }
        return quoteService.quoteUpload(file, filamentType, colorOption, printQuality, quantity, rateLimitProperties.clientKey(request));
    }

    @GetMapping("/sample")
    public QuoteResponse quoteSample(
            @RequestParam String fileName,
            @RequestParam(defaultValue = "PLA") String filament,
            @RequestParam(required = false) String color,
            @RequestParam(defaultValue = "medium") String quality,
            @RequestParam(defaultValue = "1") int quantity,
            HttpServletRequest request) {
        Path inputFile = sampleModelsDir.resolve(fileName).normalize();
        if (!inputFile.startsWith(sampleModelsDir) || !Files.isRegularFile(inputFile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample file not found.");
        }
        FilamentType filamentType = FilamentType.fromInput(filament);
        return quoteService.quoteExistingFile(inputFile, filamentType, catalog.resolve(filamentType, color),
                PrintQuality.fromInput(quality), quantity, rateLimitProperties.clientKey(request));
    }

    @GetMapping("/options")
    public QuoteOptions options() {
        List<QuoteOptions.FilamentOption> filaments = Arrays.stream(FilamentType.values())
                .map(type -> new QuoteOptions.FilamentOption(
                        type.name(), type.label(), type.profileName(), catalog.colorsFor(type)))
                .toList();
        List<QuoteOptions.QualityOption> qualities = Arrays.stream(PrintQuality.values())
                .map(tier -> new QuoteOptions.QualityOption(tier.code(), tier.label(), tier.processProfileName(),
                        tier.layerHeightMm(), tier.baseLeadTimeDays()))
                .toList();
        return new QuoteOptions(filaments, qualities, quoteService.maxQuantity());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(ModelNotFoundException.class)
    public ProblemDetail handleModelNotFound(ModelNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setProperty("code", "model-expired");
        return problem;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleTooLarge(MaxUploadSizeExceededException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.PAYLOAD_TOO_LARGE, "The uploaded model exceeds the size limit.");
    }

    @ExceptionHandler(SlicingException.class)
    public ProblemDetail handleSlicingFailure(SlicingException e) {
        log.warn("Quote failed: {}", e.getMessage(), e);
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }

    @ExceptionHandler(RateLimitExceededException.class)
    public ResponseEntity<ProblemDetail> handleRateLimit(RateLimitExceededException e) {
        return retryLater(HttpStatus.TOO_MANY_REQUESTS, e.getMessage(), e.retryAfterSeconds());
    }

    @ExceptionHandler(SlicerBusyException.class)
    public ResponseEntity<ProblemDetail> handleBusy(SlicerBusyException e) {
        return retryLater(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e.retryAfterSeconds());
    }

    private ResponseEntity<ProblemDetail> retryLater(HttpStatus status, String detail, long retryAfterSeconds) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setProperty("retryAfterSeconds", retryAfterSeconds);
        String supportEmail = rateLimitProperties.supportEmail();
        if (supportEmail != null && !supportEmail.isBlank()) {
            problem.setProperty("supportEmail", supportEmail);
        }
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(problem);
    }
}
