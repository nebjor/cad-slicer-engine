package com.slicercore.service;

import com.slicercore.config.ModelStoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Keeps uploaded models for a while so that a re-quote with another filament or quality sends
 * only the model's id instead of the file again. Models are keyed by their content hash, so
 * the id doubles as the quote cache's model key and a repeated upload refreshes the same
 * entry. Entries expire after the configured time and the least recently used ones make room
 * when the size cap is reached; a lost file is treated like an expired entry.
 */
@Component
public class UploadedModelStore {

    private static final Logger log = LoggerFactory.getLogger(UploadedModelStore.class);
    private static final Pattern CONTENT_HASH = Pattern.compile("[0-9a-f]{64}");

    /** A kept model: the id clients quote with, the file on disk and what to call it. */
    public record StoredModel(String id, Path path, String displayName, long sizeBytes, Instant storedAt) {
    }

    private final Path dir;
    private final Clock clock;
    private final Duration ttl;
    private final long maxBytes;
    /** Access order, so the first entry is the least recently used. */
    private final LinkedHashMap<String, StoredModel> models = new LinkedHashMap<>(16, 0.75f, true);
    private long totalBytes;

    public UploadedModelStore(ModelStoreProperties properties, Clock clock) {
        this.dir = resolveDir(properties.dir());
        this.clock = clock;
        this.ttl = Duration.ofMinutes(properties.ttlMinutes());
        this.maxBytes = properties.maxMegabytes() * 1024 * 1024;
    }

    public static boolean isValidId(String id) {
        return id != null && CONTENT_HASH.matcher(id).matches();
    }

    /**
     * Moves a staged file into the store under its content hash. A model already present
     * under that hash is replaced, which also refreshes its expiry.
     */
    public synchronized StoredModel put(String id, Path staged, String extension, String displayName) throws IOException {
        if (!isValidId(id)) {
            throw new IllegalArgumentException("A model id must be a SHA-256 hex digest.");
        }
        Path target = dir.resolve(id + extension);
        Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);

        StoredModel previous = models.remove(id);
        if (previous != null) {
            totalBytes -= previous.sizeBytes();
            if (!previous.path().equals(target)) {
                Files.deleteIfExists(previous.path());
            }
        }
        StoredModel model = new StoredModel(id, target, displayName, Files.size(target), clock.instant());
        models.put(id, model);
        totalBytes += model.sizeBytes();
        evict();
        return model;
    }

    /** The model with this id if it is still kept; looking it up counts as recent use. */
    public synchronized Optional<StoredModel> find(String id) {
        if (!isValidId(id)) {
            return Optional.empty();
        }
        evict();
        StoredModel model = models.get(id);
        if (model == null) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(model.path())) {
            log.warn("Stored model {} vanished from {}", id, model.path());
            remove(model);
            return Optional.empty();
        }
        return Optional.of(model);
    }

    public synchronized int size() {
        return models.size();
    }

    private void evict() {
        Instant cutoff = clock.instant().minus(ttl);
        Iterator<Map.Entry<String, StoredModel>> entries = models.entrySet().iterator();
        while (entries.hasNext()) {
            StoredModel model = entries.next().getValue();
            if (model.storedAt().isBefore(cutoff)) {
                entries.remove();
                totalBytes -= model.sizeBytes();
                deleteQuietly(model);
            }
        }
        Iterator<Map.Entry<String, StoredModel>> leastRecent = models.entrySet().iterator();
        while (totalBytes > maxBytes && leastRecent.hasNext()) {
            StoredModel model = leastRecent.next().getValue();
            leastRecent.remove();
            totalBytes -= model.sizeBytes();
            deleteQuietly(model);
        }
    }

    private void remove(StoredModel model) {
        if (models.remove(model.id()) != null) {
            totalBytes -= model.sizeBytes();
        }
        deleteQuietly(model);
    }

    private static void deleteQuietly(StoredModel model) {
        try {
            Files.deleteIfExists(model.path());
        } catch (IOException e) {
            log.warn("Could not delete stored model {}: {}", model.path(), e.getMessage());
        }
    }

    private static Path resolveDir(String configured) {
        try {
            if (configured == null || configured.isBlank()) {
                return Files.createTempDirectory("uploaded-models-");
            }
            return Files.createDirectories(Path.of(configured).toAbsolutePath().normalize());
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create the model store directory", e);
        }
    }
}
