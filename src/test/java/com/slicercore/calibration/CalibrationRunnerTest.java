package com.slicercore.calibration;

import com.slicercore.config.ModelStoreProperties;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.config.SlicerCapacityProperties;
import com.slicercore.slicer.FilamentType;
import com.slicercore.slicer.PrintQuality;
import com.slicercore.slicer.SliceProfile;
import com.slicercore.order.LocalOrderFileStorage;
import com.slicercore.order.OrderFileStorage;
import com.slicercore.order.OrderRepository;
import com.slicercore.quote.QuotePricingService;
import com.slicercore.slicer.SlicerCapacityGate;
import com.slicercore.slicer.SlicerExecutionService;
import com.slicercore.slicer.SlicerProfileResolver;
import com.slicercore.upload.UploadedModelStore;
import com.slicercore.support.MutableClock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The matrix against a shell script that plays the Bambu CLI: it answers {@code --help} with
 * a version banner, slices a single copy in 1000 s and a batch of N in 1000 N - 300 (N - 1) s,
 * and fails every run with the PCTG profile. Like {@link com.slicercore.slicer.SlicerExecutionServiceTest}
 * it needs a POSIX shell and skips itself without one.
 */
class CalibrationRunnerTest {

    private static final String FAKE_SLICER = """
            #!/bin/sh
            if [ "$1" = "--help" ]; then printf 'BambuStudio-02.01.01.52:\\nUsage: bambu-studio-cli [options]\\n'; exit 0; fi
            out=""; prev=""; mode="slice"; n=1; fil=""
            for a in "$@"; do
              if [ "$prev" = "--outputdir" ]; then out="$a"; fi
              if [ "$prev" = "--clone-objects" ]; then n="$a"; fi
              if [ "$prev" = "--load-filaments" ]; then fil="$a"; fi
              if [ "$a" = "--info" ]; then mode="info"; fi
              prev="$a"
            done
            printf '%s\\n' "$@" > "$out/args.txt"
            if [ "$mode" = "info" ]; then printf '[model.stl]\\nnumber_of_facets = 10\\nmanifold = yes\\nvolume = 1.0\\n'; exit 0; fi
            case "$fil" in *PCTG*) echo 'got error when loading the filament'; exit 7;; esac
            secs=$((1000 * n - 300 * (n - 1)))
            len=$((500 * n))
            printf '{"return_code": 0, "error_string": "Success.", "layer_height": 0.2, "sliced_plates": [{"total_predication": %s, "filaments": [], "objects": [{"name": "a", "triangle_count": %s, "bbox": {"x": 0, "y": 0, "z": 0, "width": 60, "depth": 31, "height": 48}}]}]}' "$secs" $((10 * n)) > "$out/result.json"
            printf '; total filament length [mm] : %s\\n' "$len" > "$out/plate_1.gcode"
            """;
    private static final byte[] MODEL_BYTES = "solid benchy endsolid benchy".getBytes(StandardCharsets.UTF_8);
    private static final String SHA = "a".repeat(64);

    private static String shell;

    @TempDir
    Path tempDir;

    private final MutableClock clock = MutableClock.at("2026-10-22T03:00:00Z");
    private final CalibrationQueueRepository entries = mock(CalibrationQueueRepository.class);
    private final SliceCalibrationRepository rows = mock(SliceCalibrationRepository.class);
    private final List<SliceCalibration> written = new ArrayList<>();
    private LocalOrderFileStorage orderFiles;
    private CalibrationQueue queue;
    private SlicerCapacityGate gate;
    private CalibrationRunner runner;

    @BeforeAll
    static void requiresPosixShell() {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            boolean shOnPath = Arrays.stream(System.getenv("PATH").split(File.pathSeparator))
                    .anyMatch(dir -> new File(dir, "sh.exe").isFile());
            assumeTrue(shOnPath, "fake slicer is a POSIX shell script; needs sh (Git Bash) on the PATH");
            shell = "sh";
        }
    }

    @BeforeEach
    void wire() throws IOException {
        Path script = tempDir.resolve("fake-slicer.sh");
        Files.writeString(script, FAKE_SLICER);
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
        String binary = script.toAbsolutePath().toString();
        SlicerExecutionService slicer = new SlicerExecutionService(shell == null ? binary : shell + " " + binary, 30, 20);

        SlicerProfileResolver profiles = mock(SlicerProfileResolver.class);
        when(profiles.resolve(any(), any())).thenAnswer(invocation -> {
            FilamentType filament = invocation.getArgument(0);
            PrintQuality quality = invocation.getArgument(1);
            return new SliceProfile(tempDir.resolve("machine.json"), tempDir.resolve(quality.code() + ".json"),
                    tempDir.resolve(filament.name() + ".json"));
        });

        orderFiles = new LocalOrderFileStorage(tempDir.resolve("order-files"));
        UploadedModelStore modelStore = new UploadedModelStore(
                new ModelStoreProperties(tempDir.resolve("store").toString(), 60, 64), clock);
        CalibrationProperties properties = new CalibrationProperties(tempDir.resolve("calibration").toString());
        when(entries.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(rows.save(any())).thenAnswer(invocation -> {
            written.add(invocation.getArgument(0));
            return invocation.getArgument(0);
        });
        queue = new CalibrationQueue(entries, mock(OrderRepository.class), orderFiles, modelStore, properties, clock);

        gate = spy(new SlicerCapacityGate(new SlicerCapacityProperties(1, 0)));
        QuotePricingService pricing = new QuotePricingService(new QuotePricingProperties("CHF", 4, 3, 16, 1.75, 100,
                300, new QuotePricingProperties.Plate(256, 256, 10, 15), Map.of(), QuotePricingProperties.Material.UNSCALED));
        runner = new CalibrationRunner(queue, rows, slicer, profiles, gate, pricing, clock);
    }

    @Test
    void measuresEveryCellThatSlicesAndTheModelSurvivesTheOnesThatFail() throws IOException {
        CalibrationQueueEntry entry = orderEntry();

        int measured = runner.calibrate(entry);

        // 3 filaments x 3 qualities plus two batches, minus the three PCTG failures.
        assertThat(measured).isEqualTo(8);
        assertThat(written).hasSize(8);
        assertThat(written).extracting(SliceCalibration::getFilament).doesNotContain("PCTG");
        assertThat(written).extracting(SliceCalibration::getCopies).containsExactly(1, 1, 1, 1, 1, 1, 4, 10);
        assertThat(entry.status()).isEqualTo(CalibrationQueueEntry.Status.DONE);
        assertThat(entry.getCellsWritten()).isEqualTo(8);
        assertThat(entry.getError()).isNull();
        assertThat(entry.getStartedAt()).isEqualTo(clock.instant());
        assertThat(entry.getFinishedAt()).isEqualTo(clock.instant());

        SliceCalibration single = written.stream()
                .filter(row -> row.getFilament().equals("PLA") && row.getQuality().equals("medium") && row.getCopies() == 1)
                .findFirst().orElseThrow();
        assertThat(single.getPrintSeconds()).isEqualTo(1000);
        assertThat(single.getFilamentLengthMm()).isEqualTo(500);
        // pi * 0.875^2 * 500 mm = 1202.6 mm^3 at 1.24 g/cm^3
        assertThat(single.getFilamentGrams()).isCloseTo(1.491, within(0.001));
        assertThat(single.getManifold()).isTrue();
        assertThat(single.getPlates()).isEqualTo(1);
        assertThat(single.getTriangleCount()).isEqualTo(10);
        assertThat(single.getBboxWidthMm()).isEqualTo(60);
        assertThat(single.getBboxHeightMm()).isEqualTo(48);
        assertThat(single.getModelSizeBytes()).isEqualTo(MODEL_BYTES.length);
        assertThat(single.getModelSha256()).isEqualTo(SHA);
        assertThat(single.getModelName()).isEqualTo("Benchy.stl");
        assertThat(single.getSource()).isEqualTo(CalibrationSource.ORDER);
        assertThat(single.getOrderNumber()).isEqualTo("POD-1000");
        assertThat(single.getSlicerVersion()).isEqualTo("BambuStudio-02.01.01.52");
        assertThat(single.getMeasuredAt()).isEqualTo(clock.instant());
        assertThat(single.getDurationMs()).isGreaterThanOrEqualTo(0);

        SliceCalibration batch = written.stream().filter(row -> row.getCopies() == 10).findFirst().orElseThrow();
        assertThat(batch.getFilament()).isEqualTo("PLA");
        assertThat(batch.getQuality()).isEqualTo("medium");
        assertThat(batch.getPrintSeconds()).isEqualTo(10 * 1000 - 9 * 300);
        assertThat(batch.getFilamentLengthMm()).isEqualTo(5000);
        assertThat(batch.getTriangleCount()).as("per copy").isEqualTo(10);
        assertThat(batch.getManifold()).as("batches are not inspected").isNull();

        // Every cell took one slot through the gate, with the long background wait.
        verify(gate, times(11)).run(any(), eq(CalibrationRunner.SLOT_WAIT_SECONDS));
        assertThat(gate.availableSlots()).isEqualTo(1);
    }

    @Test
    void anErasedOrderIsSkippedWithANoteAndNothingIsSliced() throws IOException {
        CalibrationQueueEntry entry = orderEntry();
        orderFiles.delete(entry.getFileKey());

        int measured = runner.calibrate(entry);

        assertThat(measured).isZero();
        assertThat(written).isEmpty();
        assertThat(entry.status()).isEqualTo(CalibrationQueueEntry.Status.FAILED);
        assertThat(entry.getError()).contains("erased").contains("POD-1000");
        verify(gate, never()).run(any(), anyLong());
    }

    @Test
    void aManualThreeMfModelGetsNoBatchCellsBecauseTheCliDoesNotCloneIt() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("calibration"));
        Files.write(dir.resolve(SHA + ".3mf"), MODEL_BYTES);
        CalibrationQueueEntry entry = CalibrationQueueEntry.queued(SHA, "Halter.3mf", SHA + ".3mf",
                CalibrationSource.MANUAL, null, clock.instant());

        int measured = runner.calibrate(entry);

        assertThat(measured).isEqualTo(6);
        assertThat(written).extracting(SliceCalibration::getCopies).containsOnly(1);
        assertThat(written).extracting(SliceCalibration::getSource).containsOnly(CalibrationSource.MANUAL);
        assertThat(written).extracting(SliceCalibration::getOrderNumber).containsOnlyNulls();
        assertThat(entry.status()).isEqualTo(CalibrationQueueEntry.Status.DONE);
        // The batch cells never reach the slicer.
        verify(gate, times(9)).run(any(), anyLong());
    }

    @Test
    void aModelWhoseEveryCellFailsIsMarkedFailedWithTheLastReason() throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve("calibration"));
        // The fake fails every run with the PCTG profile, so a resolver that answers PCTG for
        // every cell makes the whole matrix fail.
        Files.write(dir.resolve(SHA + ".stl"), MODEL_BYTES);
        CalibrationQueueEntry entry = CalibrationQueueEntry.queued(SHA, "x.stl", SHA + ".stl",
                CalibrationSource.MANUAL, null, clock.instant());
        SlicerProfileResolver allPctg = mock(SlicerProfileResolver.class);
        when(allPctg.resolve(any(), any())).thenReturn(new SliceProfile(tempDir.resolve("m.json"),
                tempDir.resolve("p.json"), tempDir.resolve("PCTG.json")));
        CalibrationRunner failing = new CalibrationRunner(queue, rows, slicerOf(entryBinary()), allPctg, gate,
                new QuotePricingService(new QuotePricingProperties("CHF", 4, 3, 16, 1.75, 100, 300,
                        new QuotePricingProperties.Plate(256, 256, 10, 15), Map.of(), QuotePricingProperties.Material.UNSCALED)),
                clock);

        int measured = failing.calibrate(entry);

        assertThat(measured).isZero();
        assertThat(entry.status()).isEqualTo(CalibrationQueueEntry.Status.FAILED);
        assertThat(entry.getError()).contains("No cell could be sliced").contains("exit code 7");
    }

    private String entryBinary() {
        String binary = tempDir.resolve("fake-slicer.sh").toAbsolutePath().toString();
        return shell == null ? binary : shell + " " + binary;
    }

    private static SlicerExecutionService slicerOf(String binary) {
        return new SlicerExecutionService(binary, 30, 20);
    }

    /** An order item's model in the order-files storage, queued as the order placement would. */
    private CalibrationQueueEntry orderEntry() throws IOException {
        OrderFileStorage.StoredFile stored = orderFiles.store(new ByteArrayInputStream(MODEL_BYTES), "stl");
        return CalibrationQueueEntry.queued(SHA, "Benchy.stl", stored.key(), CalibrationSource.ORDER, "POD-1000",
                clock.instant());
    }
}
