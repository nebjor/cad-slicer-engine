package com.slicercore.service;

import com.slicercore.config.QuotePricingProperties;
import com.slicercore.config.RateLimitProperties;
import com.slicercore.config.ModelStoreProperties;
import com.slicercore.config.SlicerCapacityProperties;
import com.slicercore.model.BoundingBox;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.QuoteResponse;
import com.slicercore.model.ModelNotFoundException;
import com.slicercore.model.UploadedModel;
import com.slicercore.model.RateLimitExceededException;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The orchestration rules that protect the host: cache hits must not touch the slicer or the
 * rate limit, rejected input must not start anything, and staging directories must be removed.
 */
class QuoteServiceTest {

    private static final String CLIENT = "203.0.113.7";
    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", null);
    private static final ColorOption RED = new ColorOption("red", "Red", "#d62828", null);

    @TempDir
    Path tempDir;

    private final SlicerExecutionService slicer = mock(SlicerExecutionService.class);
    private final SlicerProfileResolver resolver = mock(SlicerProfileResolver.class);
    private final MutableClock clock = MutableClock.at("2026-10-18T12:00:00Z");

    private QuoteService service;

    @BeforeEach
    void wireService() {
        QuotePricingProperties pricing = new QuotePricingProperties("CHF", 5, 4, 3, 16, 1.75, 20,
                Map.of(FilamentType.PLA, 35.0, FilamentType.PETG, 42.0));
        RateLimitProperties rateLimit = new RateLimitProperties(true, 1, 100, false, "");

        when(resolver.resolve(any(), any())).thenReturn(new SliceProfile(
                tempDir.resolve("machine.json"), tempDir.resolve("process.json"), tempDir.resolve("filament.json")));
        when(resolver.machineProfileName()).thenReturn("Test A1");
        when(slicer.executeSlicingQuote(anyList(), any(), any(), anyInt())).thenAnswer(invocation -> {
            int copies = invocation.getArgument(3);
            return new SliceQuoteResult("model.stl", List.of(), tempDir.resolve("result.json"), 0, "Success.",
                    0.2, copies, 1, 2239.0 * copies, 225154, 3880.6 * copies, 0, new BoundingBox(60, 31, 48),
                    List.of(), true);
        });

        service = new QuoteService(slicer, resolver, new QuotePricingService(pricing), pricing,
                new QuoteCache(clock, 16, 60), new QuoteRateLimiter(rateLimit, clock),
                new SlicerCapacityGate(new SlicerCapacityProperties(2, 1)),
                new UploadedModelStore(new ModelStoreProperties(tempDir.resolve("models").toString(), 60, 512), clock));
    }

    @Test
    void aKeptModelIsQuotedByIdAndSharesTheCacheWithUploads() {
        UploadedModel kept = service.storeUpload(stl("cube.stl"));
        assertThat(kept.modelId()).hasSize(64);
        assertThat(kept.fileName()).isEqualTo("cube.stl");

        QuoteResponse byId = service.quoteStoredModel(kept.modelId(), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        assertThat(byId.fileName()).isEqualTo("cube.stl");

        // Same content, same cache key: uploading the file again for that combination does not slice.
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, RED, PrintQuality.MEDIUM, 1, CLIENT);
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    @Test
    void anUnknownModelIdIsRejectedBeforeTheSlicer() {
        assertThatThrownBy(() -> service.quoteStoredModel("f".repeat(64), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT))
                .isInstanceOf(ModelNotFoundException.class);

        verify(slicer, never()).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    @Test
    void identicalUploadIsServedFromCacheWithoutSlicingAgain() {
        QuoteResponse first = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse second = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(second).isEqualTo(first);
        assertThat(first.fileName()).isEqualTo("cube.stl");
        assertThat(first.totalPriceChf()).isEqualTo(8.0);
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), eq(1));
    }

    @Test
    void cacheHitDoesNotConsumeRateLimitButNewSliceDoes() {
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        // The limiter allows one start per minute; the cached repeat did not count, this does.
        assertThatThrownBy(() -> service.quoteUpload(stl("cube.stl"), FilamentType.PETG, BLACK, PrintQuality.MEDIUM, 1, CLIENT))
                .isInstanceOf(RateLimitExceededException.class);
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    @Test
    void multiCopyThreeMfIsExportedToStlFirst() {
        Path exported = tempDir.resolve("obj_1_model.stl");
        when(slicer.exportObjectsAsStl(any(), any())).thenReturn(List.of(exported));

        service.quoteUpload(new MockMultipartFile("file", "boat.3mf", "application/octet-stream", bytes("PK3mf")),
                FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 4, CLIENT);

        verify(slicer).exportObjectsAsStl(any(), any());
        verify(slicer).executeSlicingQuote(eq(List.of(exported)), any(), any(), eq(4));
    }

    @Test
    void singleCopyThreeMfIsSlicedDirectly() {
        service.quoteUpload(new MockMultipartFile("file", "boat.3mf", "application/octet-stream", bytes("PK3mf")),
                FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        verify(slicer, never()).exportObjectsAsStl(any(), any());
        verify(slicer).executeSlicingQuote(anyList(), any(), any(), eq(1));
    }

    @Test
    void stagingDirectoryIsRemovedAfterwards() {
        ArgumentCaptor<Path> outputDir = ArgumentCaptor.forClass(Path.class);

        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        verify(slicer).executeSlicingQuote(anyList(), outputDir.capture(), any(), anyInt());
        assertThat(outputDir.getValue()).doesNotExist();
    }

    @Test
    void clientPathIsStrippedFromTheEchoedFileName() {
        QuoteResponse quote = service.quoteUpload(stl("C:\\Users\\me\\..\\cube.stl"),
                FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(quote.fileName()).isEqualTo("cube.stl");
    }

    @Test
    void rejectedInputNeverReachesTheSlicer() {
        assertThatThrownBy(() -> service.quoteUpload(stl("model.glb"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Only .stl and .3mf");
        assertThatThrownBy(() -> service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 21, CLIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 20");
        assertThatThrownBy(() -> service.quoteUpload(
                new MockMultipartFile("file", "empty.stl", "application/octet-stream", new byte[0]),
                FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");

        verify(slicer, never()).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    @Test
    void existingFileIsHashedForTheCacheToo() throws Exception {
        Path sample = Files.writeString(tempDir.resolve("sample.stl"), "solid sample endsolid sample");

        service.quoteExistingFile(sample, FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        service.quoteExistingFile(sample, FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        verify(slicer, times(1)).executeSlicingQuote(eq(List.of(sample)), any(), any(), eq(1));
    }

    @Test
    void colourChangeReusesTheCachedSliceInTheNewColour() {
        QuoteResponse black = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse red = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, RED, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(red.color()).isEqualTo(RED);
        assertThat(red.totalPriceChf()).isEqualTo(black.totalPriceChf());
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    @Test
    void slicerWarningsAndTheManifoldCheckTravelWithTheCachedSlice() {
        when(slicer.executeSlicingQuote(anyList(), any(), any(), anyInt())).thenReturn(
                new SliceQuoteResult("model.stl", List.of(), tempDir.resolve("result.json"), 0, "Success.",
                        0.2, 1, 1, 2239.0, 225154, 3880.6, 0, new BoundingBox(60, 31, 48),
                        List.of("Mesh errors reported by the slicer: open_edges = 12"), false));

        QuoteResponse fresh = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse cached = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, RED, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(fresh.warnings()).containsExactly("Mesh errors reported by the slicer: open_edges = 12");
        assertThat(fresh.manifold()).isFalse();
        assertThat(cached.warnings()).isEqualTo(fresh.warnings());
        assertThat(cached.manifold()).isFalse();
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    @Test
    void aDearerColourIsRepricedFromTheCachedSliceWithoutSlicingAgain() {
        ColorOption silk = new ColorOption("silk", "Silk", "#c94fb4", 48.0);
        QuoteResponse black = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse silky = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, silk, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(silky.materialCostChf()).isGreaterThan(black.materialCostChf());
        assertThat(silky.totalFilamentGrams()).isEqualTo(black.totalFilamentGrams());
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), anyInt());
        verify(slicer, times(1)).executeSlicingQuote(anyList(), any(), any(), anyInt());
    }

    private static MockMultipartFile stl(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes("solid cube endsolid cube"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
