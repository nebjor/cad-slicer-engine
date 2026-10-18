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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Orchestrates one quote: stage the model in a private temp directory, answer from the cache
 * when the same model and parameters were quoted recently, otherwise charge the client's rate
 * limit, take a slicer slot, slice the requested quantity as one arranged batch, price the
 * result, and clean up.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final SlicerExecutionService slicerExecutionService;
    private final SlicerProfileResolver profileResolver;
    private final QuotePricingService pricingService;
    private final QuotePricingProperties pricing;
    private final QuoteCache cache;
    private final QuoteRateLimiter rateLimiter;
    private final SlicerCapacityGate capacityGate;

    public QuoteService(SlicerExecutionService slicerExecutionService,
                        SlicerProfileResolver profileResolver,
                        QuotePricingService pricingService,
                        QuotePricingProperties pricing,
                        QuoteCache cache,
                        QuoteRateLimiter rateLimiter,
                        SlicerCapacityGate capacityGate) {
        this.slicerExecutionService = slicerExecutionService;
        this.profileResolver = profileResolver;
        this.pricingService = pricingService;
        this.pricing = pricing;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.capacityGate = capacityGate;
    }

    /** Largest quantity accepted; each copy adds slicing time, so this bounds request cost. */
    public int maxQuantity() {
        return pricing.maxQuantity();
    }

    /**
     * @param clientKey identifies the caller for rate limiting, typically the client address
     */
    public QuoteResponse quoteUpload(MultipartFile file, FilamentType filament, PrintQuality quality, int quantity,
                                     String clientKey) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty model file is required.");
        }
        String originalName = file.getOriginalFilename() == null ? "model" : file.getOriginalFilename();
        String extension = supportedExtension(originalName);
        validateQuantity(quantity);

        Path workDir = createWorkDir();
        try {
            Path stagedModel = workDir.resolve("model" + extension);
            String contentHash;
            try (InputStream in = file.getInputStream()) {
                contentHash = copyAndHash(in, stagedModel);
            } catch (IOException e) {
                throw new SlicingException("Failed to store the uploaded model.", e);
            }
            return quoteCached(contentHash, stagedModel, workDir, displayName(originalName),
                    filament, quality, quantity, clientKey);
        } finally {
            deleteRecursively(workDir);
        }
    }

    public QuoteResponse quoteExistingFile(Path inputFile, FilamentType filament, PrintQuality quality, int quantity,
                                           String clientKey) {
        supportedExtension(inputFile.getFileName().toString());
        validateQuantity(quantity);

        Path workDir = createWorkDir();
        try {
            String contentHash;
            try (InputStream in = Files.newInputStream(inputFile)) {
                contentHash = copyAndHash(in, null);
            } catch (IOException e) {
                throw new SlicingException("Failed to read the model.", e);
            }
            return quoteCached(contentHash, inputFile, workDir, inputFile.getFileName().toString(),
                    filament, quality, quantity, clientKey);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private QuoteResponse quoteCached(String contentHash, Path model, Path outputDir, String displayName,
                                      FilamentType filament, PrintQuality quality, int quantity, String clientKey) {
        String cacheKey = QuoteCache.key(contentHash, filament, quality, quantity);
        return cache.get(cacheKey)
                .map(cached -> {
                    log.info("Quote cache hit for {} ({} x{})", displayName, filament, quantity);
                    return cached;
                })
                .orElseGet(() -> {
                    rateLimiter.recordStart(clientKey);
                    QuoteResponse quote = capacityGate.run(
                            () -> quoteStagedModel(model, outputDir, displayName, filament, quality, quantity));
                    cache.put(cacheKey, quote);
                    return quote;
                });
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

    /** Streams the input to {@code target} (or nowhere when null) and returns its SHA-256 hex. */
    static String copyAndHash(InputStream in, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
            if (target == null) {
                digesting.transferTo(OutputStream.nullOutputStream());
            } else {
                Files.copy(digesting, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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
