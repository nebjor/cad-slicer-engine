package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
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
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Orchestrates one quote: stage the model in a private temp directory, slice the requested
 * quantity as one arranged batch with the profiles for the requested filament and quality,
 * price the result, and clean up.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final SlicerExecutionService slicerExecutionService;
    private final SlicerProfileResolver profileResolver;
    private final QuotePricingService pricingService;
    private final QuotePricingProperties pricing;

    public QuoteService(SlicerExecutionService slicerExecutionService,
                        SlicerProfileResolver profileResolver,
                        QuotePricingService pricingService,
                        QuotePricingProperties pricing) {
        this.slicerExecutionService = slicerExecutionService;
        this.profileResolver = profileResolver;
        this.pricingService = pricingService;
        this.pricing = pricing;
    }

    /** Largest quantity accepted; each copy adds slicing time, so this bounds request cost. */
    public int maxQuantity() {
        return pricing.maxQuantity();
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

        // The CLI refuses to clone objects loaded from a 3MF, so a multi-copy 3MF quote goes
        // through an STL export first. A single copy is sliced directly from the 3MF.
        List<Path> inputs = List.of(model);
        if (quantity > 1 && model.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".3mf")) {
            Path exportDir = outputDir.resolve("stl-export");
            try {
                Files.createDirectories(exportDir);
            } catch (IOException e) {
                throw new SlicingException("Failed to create the STL export directory.", e);
            }
            inputs = slicerExecutionService.exportObjectsAsStl(model, exportDir);
        }

        SliceQuoteResult slice = slicerExecutionService.executeSlicingQuote(inputs, outputDir, profile, quantity);
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

    private void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > maxQuantity()) {
            throw new IllegalArgumentException("Quantity must be between 1 and " + maxQuantity() + ".");
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
