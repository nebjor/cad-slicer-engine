package com.slicercore.service;

import com.slicercore.config.ModelStoreProperties;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadedModelStoreTest {

    private static final String ID_A = "a".repeat(64);
    private static final String ID_B = "b".repeat(64);
    private static final String ID_C = "c".repeat(64);

    @TempDir
    Path dir;

    private final MutableClock clock = MutableClock.at("2026-10-20T12:00:00Z");

    @Test
    void keepsAStagedModelUnderItsIdAndHandsItBack() throws IOException {
        UploadedModelStore store = store(512);
        Path staged = staged("cube-a.stl", 100);

        UploadedModelStore.StoredModel kept = store.put(ID_A, staged, ".stl", "cube.stl");

        assertThat(Files.exists(staged)).as("staged file is moved, not copied").isFalse();
        assertThat(kept.sizeBytes()).isEqualTo(100);
        assertThat(store.find(ID_A)).hasValueSatisfying(model -> {
            assertThat(model.displayName()).isEqualTo("cube.stl");
            assertThat(model.path()).exists().hasFileName(ID_A + ".stl");
        });
    }

    @Test
    void expiredModelsDisappearWithTheirFiles() throws IOException {
        UploadedModelStore store = store(512);
        Path kept = store.put(ID_A, staged("cube-a.stl", 100), ".stl", "cube.stl").path();

        clock.advance(Duration.ofMinutes(59));
        assertThat(store.find(ID_A)).isPresent();

        clock.advance(Duration.ofMinutes(2));
        assertThat(store.find(ID_A)).isEmpty();
        assertThat(Files.exists(kept)).isFalse();
    }

    @Test
    void theLeastRecentlyUsedModelsMakeRoomAtTheSizeCap() throws IOException {
        UploadedModelStore store = store(1);
        int fourHundredKb = 400 * 1024;
        store.put(ID_A, staged("a.stl", fourHundredKb), ".stl", "a.stl");
        store.put(ID_B, staged("b.stl", fourHundredKb), ".stl", "b.stl");
        assertThat(store.find(ID_A)).as("touching A makes B the least recent").isPresent();

        store.put(ID_C, staged("c.stl", fourHundredKb), ".stl", "c.stl");

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.find(ID_B)).isEmpty();
        assertThat(store.find(ID_A)).isPresent();
        assertThat(store.find(ID_C)).isPresent();
    }

    @Test
    void onlyContentHashesAreAcceptedAsIds() throws IOException {
        UploadedModelStore store = store(512);

        assertThat(store.find("../../etc/passwd")).isEmpty();
        assertThatThrownBy(() -> store.put("not-a-hash", staged("x.stl", 10), ".stl", "x.stl"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aFileRemovedBehindTheStoresBackCountsAsExpired() throws IOException {
        UploadedModelStore store = store(512);
        Path kept = store.put(ID_A, staged("cube-a.stl", 100), ".stl", "cube.stl").path();
        Files.delete(kept);

        assertThat(store.find(ID_A)).isEmpty();
        assertThat(store.size()).isZero();
    }

    private UploadedModelStore store(long maxMegabytes) {
        return new UploadedModelStore(new ModelStoreProperties(dir.resolve("store").toString(), 60, maxMegabytes), clock);
    }

    private Path staged(String name, int bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, new byte[bytes]);
        return file;
    }
}
