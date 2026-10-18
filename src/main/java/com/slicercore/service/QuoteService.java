package com.slicercore.service;

import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.model.SlicingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Orchestrates one quote: stage the model in a private temp directory, slice it with the
 * profiles for the requested filament and quality, price the result, and clean up.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);
    public static final int MAX_QUANTITY = 100;

    private final SlicerExecutionService slicerExecutionService;
    private final SlicerProfileResolver profileResolver;
    private final QuotePricingService pricingService;

    public QuoteService(SlicerExecutionService slicerExecutionService,
                        SlicerProfileResolver profileResolver,
                        QuotePricingService pricingService) {
        this.slicerExecutionService = slicerExecutionService;
        this.profileResolver = profileResolver;
        this.pricingService = pricingService;
    }

    public QuoteResponse quoteUpload(MultipartFile file, FilamentType filament, PrintQuality quality, int quantity) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty model file is required.");
        }
        String originalName = file.getOriginalFilename() == null ? "model" : file.getOriginalFilename();
        String extension = supportedExtension(originalName);
        validateQuantity(quantity);

        Path workDir = createWorkDir();
        try {
            Path stagedModel = workDir.resolve("model" + extension);
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, stagedModel, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new SlicingException("Failed to store the uploaded model.", e);
            }
            return quoteStagedModel(stagedModel, workDir, displayName(originalName), filament, quality, quantity);
        } finally {
            deleteRecursively(workDir);
        }
    }

    public QuoteResponse quoteExistingFile(Path inputFile, FilamentType filament, PrintQuality quality, int quantity) {
        supportedExtension(inputFile.getFileName().toString());
        validateQuantity(quantity);

        Path workDir = createWorkDir();
        try {
            return quoteStagedModel(inputFile, workDir, inputFile.getFileName().toString(), filament, quality, quantity);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private QuoteResponse quoteStagedModel(Path model, Path outputDir, String displayName,
                                           FilamentType filament, PrintQuality quality, int quantity) {
        SliceProfile profile = profileResolver.resolve(filament, quality);
        SliceQuoteResult slice = slicerExecutionService.executeSlicingQuote(model, outputDir, profile);
        return pricingService.price(slice, displayName, profileResolver.machineProfileName(), filament, quality, quantity);
    }

    private static String supportedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".stl")) {
            return ".stl";
        }
        if (lower.endsWith(".3mf")) {
            return ".3mf";
        }
        throw new IllegalArgumentException("Only .stl and .3mf files are supported.");
    }

    private static void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > MAX_QUANTITY) {
            throw new IllegalArgumentException("Quantity must be between 1 and " + MAX_QUANTITY + ".");
        }
    }

    /** Strip any client-supplied path components; only the base name is echoed back. */
    private static String displayName(String originalName) {
        String base = originalName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : base;
    }

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("slice-quote-");
        } catch (IOException e) {
            throw new SlicingException("Failed to create a working directory for slicing.", e);
        }
    }

    private static void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("Could not delete {}: {}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            log.warn("Could not clean up {}: {}", dir, e.getMessage());
        }
    }
}
