package com.slicercore.order;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** Order files on the local filesystem, one flat directory, UUID keys. */
@Component
public class LocalOrderFileStorage implements OrderFileStorage {

    private static final Logger log = LoggerFactory.getLogger(LocalOrderFileStorage.class);
    private static final Pattern KEY = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(stl|3mf)");

    private final Path baseDir;

    @Autowired
    public LocalOrderFileStorage(OrderProperties properties) {
        this(Path.of(properties.filesDir()));
    }

    /** For tests: point the storage at any directory. */
    LocalOrderFileStorage(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    @Override
    public StoredFile store(InputStream content, String extension) throws IOException {
        String normalizedExtension = extension.toLowerCase(Locale.ROOT).replace(".", "");
        String key = UUID.randomUUID() + "." + normalizedExtension;
        requireValidKey(key);

        Files.createDirectories(baseDir);
        Path target = baseDir.resolve(key);

        MessageDigest digest = sha256();
        long size;
        try (DigestInputStream digesting = new DigestInputStream(content, digest)) {
            size = Files.copy(digesting, target, StandardCopyOption.REPLACE_EXISTING);
        }

        log.info("Stored order file {} ({} bytes)", key, size);
        return new StoredFile(key, HexFormat.of().formatHex(digest.digest()), size);
    }

    @Override
    public InputStream open(String key) throws IOException {
        return Files.newInputStream(resolve(key));
    }

    @Override
    public boolean delete(String key) throws IOException {
        return Files.deleteIfExists(resolve(key));
    }

    /** Only keys this class generated are accepted, so no caller can reach outside the directory. */
    private Path resolve(String key) {
        requireValidKey(key);
        Path path = baseDir.resolve(key).normalize();
        if (!path.startsWith(baseDir)) {
            throw new IllegalArgumentException("Invalid order file key.");
        }
        return path;
    }

    private static void requireValidKey(String key) {
        if (key == null || !KEY.matcher(key).matches()) {
            throw new IllegalArgumentException("Invalid order file key.");
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
