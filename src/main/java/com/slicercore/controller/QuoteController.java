package com.slicercore.controller;

import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteOptions;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SlicingException;
import com.slicercore.service.QuoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
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
 * POST /api/quotes            multipart upload of an STL/3MF plus filament, quality, quantity
 * GET  /api/quotes/sample     quote one of the bundled sample models (local development)
 * GET  /api/quotes/options    filaments, qualities and limits the backend accepts
 */
@RestController
@RequestMapping("/api/quotes")
public class QuoteController {

    private static final Logger log = LoggerFactory.getLogger(QuoteController.class);

    private final QuoteService quoteService;
    private final Path sampleModelsDir;

    public QuoteController(QuoteService quoteService,
                           @Value("${sample-models-dir:/workspace/public}") String sampleModelsDir) {
        this.quoteService = quoteService;
        this.sampleModelsDir = Path.of(sampleModelsDir).toAbsolutePath().normalize();
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public QuoteResponse quoteUpload(
            @RequestPart("file") MultipartFile file,
            @RequestParam(defaultValue = "PLA") String filament,
            @RequestParam(defaultValue = "medium") String quality,
            @RequestParam(defaultValue = "1") int quantity) {
        return quoteService.quoteUpload(file, FilamentType.fromInput(filament), PrintQuality.fromInput(quality), quantity);
    }

    @GetMapping("/sample")
    public QuoteResponse quoteSample(
            @RequestParam String fileName,
            @RequestParam(defaultValue = "PLA") String filament,
            @RequestParam(defaultValue = "medium") String quality,
            @RequestParam(defaultValue = "1") int quantity) {
        Path inputFile = sampleModelsDir.resolve(fileName).normalize();
        if (!inputFile.startsWith(sampleModelsDir) || !Files.isRegularFile(inputFile)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sample file not found.");
        }
        return quoteService.quoteExistingFile(inputFile, FilamentType.fromInput(filament), PrintQuality.fromInput(quality), quantity);
    }

    @GetMapping("/options")
    public QuoteOptions options() {
        List<QuoteOptions.FilamentOption> filaments = Arrays.stream(FilamentType.values())
                .map(type -> new QuoteOptions.FilamentOption(type.name(), type.name(), type.profileName()))
                .toList();
        List<QuoteOptions.QualityOption> qualities = Arrays.stream(PrintQuality.values())
                .map(tier -> new QuoteOptions.QualityOption(
                        tier.code(), tier.processProfileName(), tier.layerHeightMm(), tier.baseLeadTimeDays()))
                .toList();
        return new QuoteOptions(filaments, qualities, quoteService.maxQuantity());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleBadRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
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
}
