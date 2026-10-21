package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.ModelNotFoundException;
import com.slicercore.model.UploadedModel;
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
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Orchestrates one quote: stage the model in a private temp directory, answer from the cache
 * when the same model and quality were sliced recently, otherwise charge the client's rate
 * limit, take a slicer slot, slice a single copy with the reference filament, price the
 * result for the requested filament, colour and quantity, and clean up.
 *
 * Neither filament, colour nor quantity reaches the slicer: the quantity is arithmetic over
 * the single-copy slice (ADR 0006) and the filament scales it by measured factors
 * (ADR 0007), so changing any of them is always a cache hit. Only the quality is a real slice.
 */
@Service
public class QuoteService {

    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    /**
     * The filament every quote is sliced with. The factors in {@code quote.material.*} are
     * measured relative to this profile, so the two must change together.
     */
    public static final FilamentType REFERENCE_FILAMENT = FilamentType.PLA;

    private final SlicerExecutionService slicerExecutionService;
    private final SlicerProfileResolver profileResolver;
    private final QuotePricingService pricingService;
    private final QuotePricingProperties pricing;
    private final QuoteCache cache;
    private final QuoteRateLimiter rateLimiter;
    private final SlicerCapacityGate capacityGate;
    private final UploadedModelStore modelStore;

    public QuoteService(SlicerExecutionService slicerExecutionService,
                        SlicerProfileResolver profileResolver,
                        QuotePricingService pricingService,
                        QuotePricingProperties pricing,
                        QuoteCache cache,
                        QuoteRateLimiter rateLimiter,
                        SlicerCapacityGate capacityGate,
                        UploadedModelStore modelStore) {
        this.slicerExecutionService = slicerExecutionService;
        this.profileResolver = profileResolver;
        this.pricingService = pricingService;
        this.pricing = pricing;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.capacityGate = capacityGate;
        this.modelStore = modelStore;
    }

    /** Largest quantity accepted; the quantity is arithmetic over one slice, so this bounds the order size. */
    public int maxQuantity() {
        return pricing.maxQuantity();
    }

    /**
     * @param clientKey identifies the caller for rate limiting, typically the client address
     */
    public QuoteResponse quoteUpload(MultipartFile file, FilamentType filament, ColorOption color,
                                     PrintQuality quality, int quantity, String clientKey) {
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
                    filament, color, quality, quantity, clientKey);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /**
     * Keeps an upload for later quotes by id. The id is the content hash, the same key the
     * quote cache uses, so a quote by id for a combination that was already sliced is a hit.
     */
    public UploadedModel storeUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty model file is required.");
        }
        String originalName = file.getOriginalFilename() == null ? "model" : file.getOriginalFilename();
        String extension = supportedExtension(originalName);

        Path workDir = createWorkDir();
        try {
            Path stagedModel = workDir.resolve("model" + extension);
            String contentHash;
            try (InputStream in = file.getInputStream()) {
                contentHash = copyAndHash(in, stagedModel);
            } catch (IOException e) {
                throw new SlicingException("Failed to store the uploaded model.", e);
            }
            UploadedModelStore.StoredModel stored;
            try {
                stored = modelStore.put(contentHash, stagedModel, extension, displayName(originalName));
            } catch (IOException e) {
                throw new SlicingException("Failed to keep the uploaded model.", e);
            }
            log.info("Model {} kept as {} ({} bytes)", stored.displayName(), stored.id(), stored.sizeBytes());
            return new UploadedModel(stored.id(), stored.displayName(), stored.sizeBytes());
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** Quotes a model kept by {@link #storeUpload}; unknown or expired ids are rejected. */
    public QuoteResponse quoteStoredModel(String modelId, FilamentType filament, ColorOption color,
                                          PrintQuality quality, int quantity, String clientKey) {
        validateQuantity(quantity);
        UploadedModelStore.StoredModel stored = modelStore.find(modelId)
                .orElseThrow(() -> new ModelNotFoundException(modelId));

        Path workDir = createWorkDir();
        try {
            return quoteCached(stored.id(), stored.path(), workDir, stored.displayName(),
                    filament, color, quality, quantity, clientKey);
        } finally {
            deleteRecursively(workDir);
        }
    }

    public QuoteResponse quoteExistingFile(Path inputFile, FilamentType filament, ColorOption color,
                                           PrintQuality quality, int quantity, String clientKey) {
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
                    filament, color, quality, quantity, clientKey);
        } finally {
            deleteRecursively(workDir);
        }
    }

    private QuoteResponse quoteCached(String contentHash, Path model, Path outputDir, String displayName,
                                      FilamentType filament, ColorOption color, PrintQuality quality, int quantity,
                                      String clientKey) {
        String cacheKey = QuoteCache.key(contentHash, quality);
        SliceQuoteResult slice = cache.get(cacheKey)
                .map(cached -> {
                    log.info("Slice cache hit for {} ({}), priced as {} x{}", displayName, quality.code(), filament, quantity);
                    return cached;
                })
                .orElseGet(() -> {
                    rateLimiter.recordStart(clientKey);
                    SliceQuoteResult fresh = capacityGate.run(() -> sliceStagedModel(model, outputDir, quality));
                    cache.put(cacheKey, fresh);
                    return fresh;
                });
        return pricingService.price(slice, displayName, profileResolver.machineProfileName(),
                filament, color, quality, quantity);
    }

    /** Always the reference filament: the chosen one is applied by the pricing (ADR 0007). */
    private SliceQuoteResult sliceStagedModel(Path model, Path outputDir, PrintQuality quality) {
        SliceProfile profile = profileResolver.resolve(REFERENCE_FILAMENT, quality);
        return slicerExecutionService.executeSlicingQuote(model, outputDir, profile);
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
