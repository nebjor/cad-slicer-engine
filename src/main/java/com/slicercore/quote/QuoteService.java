package com.slicercore.quote;

import com.slicercore.catalog.ColorOption;
import com.slicercore.catalog.PrintQuality;
import com.slicercore.catalog.FilamentType;
import com.slicercore.slicer.SlicerCapacityGate;
import com.slicercore.slicer.SlicerExecutionService;
import com.slicercore.slicer.SlicerProfileResolver;
import com.slicercore.upload.UploadedModelStore;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.upload.ModelNotFoundException;
import com.slicercore.upload.UploadService;
import com.slicercore.slicer.SliceProfile;
import com.slicercore.slicer.SliceQuoteResult;
import com.slicercore.slicer.SlicingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
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
 *
 * Every slice that does run is handed to the {@link SliceRecorder} port afterwards, outside
 * the slicer slot: the calibration dataset (ADR 0008) grows from the shop's own work, and a
 * recorder that fails is a warning, never a failed quote.
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
    private final List<SliceRecorder> recorders;

    public QuoteService(SlicerExecutionService slicerExecutionService,
                        SlicerProfileResolver profileResolver,
                        QuotePricingService pricingService,
                        QuotePricingProperties pricing,
                        QuoteCache cache,
                        QuoteRateLimiter rateLimiter,
                        SlicerCapacityGate capacityGate,
                        UploadedModelStore modelStore,
                        List<SliceRecorder> recorders) {
        this.slicerExecutionService = slicerExecutionService;
        this.profileResolver = profileResolver;
        this.pricingService = pricingService;
        this.pricing = pricing;
        this.cache = cache;
        this.rateLimiter = rateLimiter;
        this.capacityGate = capacityGate;
        this.modelStore = modelStore;
        this.recorders = recorders;
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
        String extension = UploadService.supportedExtension(originalName);
        validateQuantity(quantity);

        Path workDir = createWorkDir();
        try {
            Path stagedModel = workDir.resolve("model" + extension);
            String contentHash;
            try (InputStream in = file.getInputStream()) {
                contentHash = UploadService.copyAndHash(in, stagedModel);
            } catch (IOException e) {
                throw new SlicingException("Failed to store the uploaded model.", e);
            }
            return quoteCached(contentHash, stagedModel, workDir, UploadService.displayName(originalName),
                    filament, color, quality, quantity, clientKey);
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** Quotes a model kept by {@link UploadService#storeUpload}; unknown or expired ids are rejected. */
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
                    long[] startedNanos = new long[1];
                    SliceQuoteResult fresh = capacityGate.run(() -> {
                        startedNanos[0] = System.nanoTime();
                        return sliceStagedModel(model, outputDir, quality);
                    });
                    cache.put(cacheKey, fresh);
                    record(contentHash, model, displayName, quality, fresh, (System.nanoTime() - startedNanos[0]) / 1_000_000);
                    return fresh;
                });
        return pricingService.price(slice, displayName, profileResolver.machineProfileName(),
                filament, color, quality, quantity);
    }

    /** The slice joins the calibration dataset (ADR 0008); nothing about that may fail the quote. */
    private void record(String contentHash, Path model, String displayName, PrintQuality quality,
                        SliceQuoteResult slice, long durationMs) {
        for (SliceRecorder recorder : recorders) {
            try {
                recorder.recordQuoteSlice(contentHash, displayName, Files.size(model), quality, slice, durationMs);
            } catch (IOException | RuntimeException e) {
                log.warn("Slice of {} ({}) was not recorded by {}: {}", displayName, quality.code(),
                        recorder.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    /** Always the reference filament: the chosen one is applied by the pricing (ADR 0007). */
    private SliceQuoteResult sliceStagedModel(Path model, Path outputDir, PrintQuality quality) {
        SliceProfile profile = profileResolver.resolve(REFERENCE_FILAMENT, quality);
        return slicerExecutionService.executeSlicingQuote(model, outputDir, profile);
    }

    private void validateQuantity(int quantity) {
        if (quantity < 1 || quantity > maxQuantity()) {
            throw new IllegalArgumentException("Quantity must be between 1 and " + maxQuantity() + ".");
        }
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
