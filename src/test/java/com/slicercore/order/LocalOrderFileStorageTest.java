package com.slicercore.order;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalOrderFileStorageTest {

    @TempDir
    Path dir;

    @Test
    void storesUnderAnOpaqueKeyAndReportsHashAndSize() throws IOException {
        LocalOrderFileStorage storage = new LocalOrderFileStorage(dir.resolve("orders"));
        byte[] content = "solid benchy endsolid benchy".getBytes(StandardCharsets.UTF_8);

        OrderFileStorage.StoredFile stored = storage.store(new ByteArrayInputStream(content), ".STL");

        assertThat(stored.key()).matches("[0-9a-f-]{36}\\.stl").doesNotContain("benchy");
        assertThat(stored.sizeBytes()).isEqualTo(content.length);
        assertThat(stored.sha256()).hasSize(64)
                .isEqualTo("5d5e1b4c9a1e0f4d2d2d1b3e9c4a7e3d8c6b2f1a0e9d8c7b6a5f4e3d2c1b0a9f".substring(0, 0)
                        + sha256Hex(content));
        assertThat(Files.exists(dir.resolve("orders").resolve(stored.key()))).isTrue();

        try (InputStream in = storage.open(stored.key())) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
        assertThat(storage.delete(stored.key())).isTrue();
        assertThat(storage.delete(stored.key())).isFalse();
    }

    @Test
    void refusesKeysItDidNotGenerate() {
        LocalOrderFileStorage storage = new LocalOrderFileStorage(dir);

        for (String key : new String[]{"../etc/passwd", "benchy.stl", "", "0000-not-a-uuid.stl",
                "123e4567-e89b-12d3-a456-426614174000.exe"}) {
            assertThatThrownBy(() -> storage.open(key))
                    .as(key)
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
