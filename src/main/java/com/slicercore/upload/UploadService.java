package com.slicercore.upload;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
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
 * Stages an uploaded model, hashes it and keeps it in the {@link UploadedModelStore}. The id
 * is the content hash, the same key the quote cache uses, so a quote by id for a combination
 * that was already sliced is a hit. The static helpers are shared with the one-shot quote
 * upload, which stages and hashes the same way without keeping the file.
 */
@Service
public class UploadService {

    private static final Logger log = LoggerFactory.getLogger(UploadService.class);

    private final UploadedModelStore modelStore;

    public UploadService(UploadedModelStore modelStore) {
        this.modelStore = modelStore;
    }

    /** Keeps an upload for later quotes by id. */
    public UploadedModel storeUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty model file is required.");
        }
        String originalName = file.getOriginalFilename() == null ? "model" : file.getOriginalFilename();
        try (InputStream in = file.getInputStream()) {
            return storeUpload(in, originalName);
        } catch (IOException e) {
            throw new UploadFailedException("Failed to store the uploaded model.", e);
        }
    }

    /** Keeps a model read from any stream (the cart re-registers a stored file for a new device). */
    public UploadedModel storeUpload(InputStream content, String originalName) {
        String extension = supportedExtension(originalName);

        Path workDir = createWorkDir();
        try {
            Path stagedModel = workDir.resolve("model" + extension);
            String contentHash;
            try {
                contentHash = copyAndHash(content, stagedModel);
            } catch (IOException e) {
                throw new UploadFailedException("Failed to store the uploaded model.", e);
            }
            UploadedModelStore.StoredModel stored;
            try {
                stored = modelStore.put(contentHash, stagedModel, extension, displayName(originalName));
            } catch (IOException e) {
                throw new UploadFailedException("Failed to keep the uploaded model.", e);
            }
            log.info("Model {} kept as {} ({} bytes)", stored.displayName(), stored.id(), stored.sizeBytes());
            return new UploadedModel(stored.id(), stored.displayName(), stored.sizeBytes());
        } finally {
            deleteRecursively(workDir);
        }
    }

    /** Streams the input to {@code target} and returns its SHA-256 hex. */
    public static String copyAndHash(InputStream in, Path target) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }

        try (DigestInputStream digesting = new DigestInputStream(in, digest)) {
            Files.copy(digesting, target, StandardCopyOption.REPLACE_EXISTING);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** The file extension the pipeline accepts, or an {@link IllegalArgumentException}. */
    public static String supportedExtension(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".stl")) {
            return ".stl";
        }
        if (lower.endsWith(".3mf")) {
            return ".3mf";
        }
        throw new IllegalArgumentException("Only .stl and .3mf files are supported.");
    }

    /** Strip any client-supplied path components; only the base name is echoed back. */
    public static String displayName(String originalName) {
        String base = originalName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        return slash >= 0 ? base.substring(slash + 1) : base;
    }

    private static Path createWorkDir() {
        try {
            return Files.createTempDirectory("slice-quote-");
        } catch (IOException e) {
            throw new UploadFailedException("Failed to create a working directory for the upload.", e);
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
