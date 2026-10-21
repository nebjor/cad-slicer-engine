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
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private final SliceRecorder recorder = mock(SliceRecorder.class);
    private final MutableClock clock = MutableClock.at("2026-10-18T12:00:00Z");

    private QuoteService service;

    @BeforeEach
    void wireService() {
        QuotePricingProperties pricing = new QuotePricingProperties("CHF", 5, 4, 3, 16, 1.75, 100, 300,
                new QuotePricingProperties.Plate(256, 256, 10, 15),
                Map.of(FilamentType.PLA, 35.0, FilamentType.PETG, 42.0, FilamentType.PCTG, 55.0),
                new QuotePricingProperties.Material(
                        Map.of(FilamentType.PLA, 1.0, FilamentType.PETG, 1.38, FilamentType.PCTG, 1.48),
                        Map.of(FilamentType.PLA, 1.0, FilamentType.PETG, 0.97, FilamentType.PCTG, 0.97)));
        RateLimitProperties rateLimit = new RateLimitProperties(true, 1, 100, false, "");

        when(resolver.resolve(any(), any())).thenReturn(new SliceProfile(
                tempDir.resolve("machine.json"), tempDir.resolve("process.json"), tempDir.resolve("filament.json")));
        when(resolver.machineProfileName()).thenReturn("Test A1");
        when(slicer.executeSlicingQuote(any(Path.class), any(), any())).thenReturn(
                new SliceQuoteResult("model.stl", List.of(), tempDir.resolve("result.json"), 0, "Success.",
                        0.2, 1, 2239.0, 225154, 3880.6, 0, new BoundingBox(60, 31, 48), List.of(), true));

        service = new QuoteService(slicer, resolver, new QuotePricingService(pricing), pricing,
                new QuoteCache(clock, 16, 60), new QuoteRateLimiter(rateLimit, clock),
                new SlicerCapacityGate(new SlicerCapacityProperties(2, 1)),
                new UploadedModelStore(new ModelStoreProperties(tempDir.resolve("models").toString(), 60, 512), clock),
                List.of(recorder));
    }

    @Test
    void everySliceIsRecordedForTheCalibrationButCacheHitsAreNot() {
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        service.quoteUpload(stl("cube.stl"), FilamentType.PETG, RED, PrintQuality.MEDIUM, 4, CLIENT);
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 10, CLIENT);

        // One slice, one row: the filament and quantity changes were cache hits.
        ArgumentCaptor<SliceQuoteResult> slice = ArgumentCaptor.forClass(SliceQuoteResult.class);
        verify(recorder, times(1)).recordQuoteSlice(anyString(), eq("cube.stl"), anyLong(), eq(PrintQuality.MEDIUM),
                slice.capture(), anyLong());
        assertThat(slice.getValue().totalPredicationSeconds()).isEqualTo(2239.0);

        // Another quality is another slice and another row (a minute later, past the one-per-minute limit).
        clock.advance(Duration.ofMinutes(1));
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.HIGH, 1, CLIENT);
        verify(recorder).recordQuoteSlice(anyString(), eq("cube.stl"), anyLong(), eq(PrintQuality.HIGH), any(), anyLong());
        verify(slicer, times(2)).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void aRecorderThatFailsDoesNotFailTheQuote() {
        doThrow(new IllegalStateException("calibration table missing")).when(recorder)
                .recordQuoteSlice(anyString(), anyString(), anyLong(), any(), any(), anyLong());

        QuoteResponse quote = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(quote.totalPriceChf()).isEqualTo(8.0);
        verify(recorder).recordQuoteSlice(anyString(), anyString(), anyLong(), any(), any(), anyLong());
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
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void anUnknownModelIdIsRejectedBeforeTheSlicer() {
        assertThatThrownBy(() -> service.quoteStoredModel("f".repeat(64), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT))
                .isInstanceOf(ModelNotFoundException.class);

        verify(slicer, never()).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void identicalUploadIsServedFromCacheWithoutSlicingAgain() {
        QuoteResponse first = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse second = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(second).isEqualTo(first);
        assertThat(first.fileName()).isEqualTo("cube.stl");
        assertThat(first.totalPriceChf()).isEqualTo(8.0);
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void everyQuantityIsPricedFromTheOneSingleCopySlice() {
        QuoteResponse one = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse ten = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 10, CLIENT);
        QuoteResponse twenty = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 20, CLIENT);

        // The slicer ran once; the quantity never reached it.
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
        assertThat(one.totalPrintTimeSeconds()).isEqualTo(2239);
        assertThat(one.plateCount()).isEqualTo(1);
        // 10 x 1939 + 300 on one plate; 20 x 1939 + 2 x 300 on two (60 x 31 mm fits 15 per plate).
        assertThat(ten.totalPrintTimeSeconds()).isEqualTo(19690);
        assertThat(ten.plateCount()).isEqualTo(1);
        assertThat(twenty.totalPrintTimeSeconds()).isEqualTo(39380);
        assertThat(twenty.plateCount()).isEqualTo(2);
        assertThat(twenty.filamentGrams()).isEqualTo(one.filamentGrams());
        assertThat(twenty.totalFilamentGrams()).isEqualTo(231.5);
    }

    @Test
    void cacheHitDoesNotConsumeRateLimitButNewSliceDoes() {
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        service.quoteUpload(stl("cube.stl"), FilamentType.PETG, BLACK, PrintQuality.MEDIUM, 5, CLIENT);

        // The limiter allows one start per minute; the cached repeats did not count, another quality does.
        assertThatThrownBy(() -> service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.HIGH, 1, CLIENT))
                .isInstanceOf(RateLimitExceededException.class);
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void everyFilamentIsPricedFromTheOneReferenceSlice() {
        QuoteResponse pla = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse petg = service.quoteUpload(stl("cube.stl"), FilamentType.PETG, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse pctg = service.quoteUpload(stl("cube.stl"), FilamentType.PCTG, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        // One slicer run with the PLA profile; the other filaments never reached the resolver.
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
        verify(resolver, times(1)).resolve(FilamentType.PLA, PrintQuality.MEDIUM);
        verify(resolver, never()).resolve(eq(FilamentType.PETG), any());
        verify(resolver, never()).resolve(eq(FilamentType.PCTG), any());

        // Each answer still names its own filament and is scaled by its factors: 2239 s x 1.38 and x 1.48,
        // 3880.6 mm x 0.97 at the filament's density.
        assertThat(pla.filament()).isEqualTo("PLA");
        assertThat(pla.totalPrintTimeSeconds()).isEqualTo(2239);
        assertThat(pla.filamentGrams()).isEqualTo(11.6);
        assertThat(petg.filament()).isEqualTo("PETG");
        assertThat(petg.totalPrintTimeSeconds()).isEqualTo(3090);
        assertThat(petg.filamentLengthMm()).isEqualTo(3764.2);
        assertThat(petg.filamentGrams()).isEqualTo(11.5);
        assertThat(pctg.filament()).isEqualTo("PCTG");
        assertThat(pctg.totalPrintTimeSeconds()).isEqualTo(3314);
        assertThat(pctg.filamentGrams()).isEqualTo(11.1);
        assertThat(petg.machineProfile()).isEqualTo("Test A1");
    }

    @Test
    void anotherQualityIsStillARealSlice() {
        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        clock.advance(Duration.ofMinutes(1));
        service.quoteUpload(stl("cube.stl"), FilamentType.PETG, BLACK, PrintQuality.HIGH, 1, CLIENT);

        verify(slicer, times(2)).executeSlicingQuote(any(Path.class), any(), any());
        verify(resolver).resolve(FilamentType.PLA, PrintQuality.MEDIUM);
        verify(resolver).resolve(FilamentType.PLA, PrintQuality.HIGH);
    }

    @Test
    void aThreeMfIsSlicedDirectlyAtAnyQuantity() {
        ArgumentCaptor<Path> input = ArgumentCaptor.forClass(Path.class);

        service.quoteUpload(new MockMultipartFile("file", "boat.3mf", "application/octet-stream", bytes("PK3mf")),
                FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 4, CLIENT);

        verify(slicer).executeSlicingQuote(input.capture(), any(), any());
        assertThat(input.getValue().getFileName().toString()).isEqualTo("model.3mf");
    }

    @Test
    void stagingDirectoryIsRemovedAfterwards() {
        ArgumentCaptor<Path> outputDir = ArgumentCaptor.forClass(Path.class);

        service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        verify(slicer).executeSlicingQuote(any(Path.class), outputDir.capture(), any());
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
        assertThatThrownBy(() -> service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 101, CLIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 100");
        assertThatThrownBy(() -> service.quoteUpload(
                new MockMultipartFile("file", "empty.stl", "application/octet-stream", new byte[0]),
                FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty");

        verify(slicer, never()).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void existingFileIsHashedForTheCacheToo() throws Exception {
        Path sample = Files.writeString(tempDir.resolve("sample.stl"), "solid sample endsolid sample");

        service.quoteExistingFile(sample, FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        service.quoteExistingFile(sample, FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);

        verify(slicer, times(1)).executeSlicingQuote(eq(sample), any(), any());
    }

    @Test
    void colourChangeReusesTheCachedSliceInTheNewColour() {
        QuoteResponse black = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse red = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, RED, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(red.color()).isEqualTo(RED);
        assertThat(red.totalPriceChf()).isEqualTo(black.totalPriceChf());
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void slicerWarningsAndTheManifoldCheckTravelWithTheCachedSlice() {
        when(slicer.executeSlicingQuote(any(Path.class), any(), any())).thenReturn(
                new SliceQuoteResult("model.stl", List.of(), tempDir.resolve("result.json"), 0, "Success.",
                        0.2, 1, 2239.0, 225154, 3880.6, 0, new BoundingBox(60, 31, 48),
                        List.of("Mesh errors reported by the slicer: open_edges = 12"), false));

        QuoteResponse fresh = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse cached = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, RED, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(fresh.warnings()).containsExactly("Mesh errors reported by the slicer: open_edges = 12");
        assertThat(fresh.manifold()).isFalse();
        assertThat(cached.warnings()).isEqualTo(fresh.warnings());
        assertThat(cached.manifold()).isFalse();
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
    }

    @Test
    void aDearerColourIsRepricedFromTheCachedSliceWithoutSlicingAgain() {
        ColorOption silk = new ColorOption("silk", "Silk", "#c94fb4", 48.0);
        QuoteResponse black = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, BLACK, PrintQuality.MEDIUM, 1, CLIENT);
        QuoteResponse silky = service.quoteUpload(stl("cube.stl"), FilamentType.PLA, silk, PrintQuality.MEDIUM, 1, CLIENT);

        assertThat(silky.materialCostChf()).isGreaterThan(black.materialCostChf());
        assertThat(silky.totalFilamentGrams()).isEqualTo(black.totalFilamentGrams());
        verify(slicer, times(1)).executeSlicingQuote(any(Path.class), any(), any());
    }

    private static MockMultipartFile stl(String name) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes("solid cube endsolid cube"));
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
