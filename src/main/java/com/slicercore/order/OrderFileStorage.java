package com.slicercore.order;

import java.io.IOException;
import java.io.InputStream;

/**
 * Where order model files live. Keys are server generated and opaque; a client filename never
 * becomes part of a key. The local implementation writes to a volume; an object-store
 * implementation can replace it behind this interface (ADR 0003).
 */
public interface OrderFileStorage {

    /** Stores the content under a new key and reports its hash and size. */
    StoredFile store(InputStream content, String extension) throws IOException;

    InputStream open(String key) throws IOException;

    /** @return true when a file was removed */
    boolean delete(String key) throws IOException;

    record StoredFile(String key, String sha256, long sizeBytes) {
    }
}
