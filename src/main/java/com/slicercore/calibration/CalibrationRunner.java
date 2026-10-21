package com.slicercore.calibration;

import com.slicercore.model.FilamentType;
import com.slicercore.model.PrintQuality;
import com.slicercore.model.SliceProfile;
import com.slicercore.model.SliceQuoteResult;
import com.slicercore.service.QuotePricingService;
import com.slicercore.service.QuoteService;
import com.slicercore.service.SlicerCapacityGate;
import com.slicercore.service.SlicerExecutionService;
import com.slicercore.service.SlicerProfileResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Slices one model the owner queued through the calibration matrix (ADR 0008): every filament
 * at every quality as a single copy, with the real filament profiles, plus two arranged
 * batches of the reference filament at standard quality. Each cell is one slicer run through the
 * capacity gate, one at a time, so customer quotes always find the other slot free; each
 * success is one row, a failed cell is a warning and the model goes on.
 */
@Service
public class CalibrationRunner {

    private static final Logger log = LoggerFactory.getLogger(CalibrationRunner.class);

    /** How long a cell waits for a slicer slot; the night is long and quotes come first. */
    static final long SLOT_WAIT_SECONDS = 600;
    /** The batches that check the quantity arithmetic: one plate, and still one plate for a Benchy. */
    static final List<Integer> BATCH_COPIES = List.of(4, 10);
    static final PrintQuality BATCH_QUALITY = PrintQuality.MEDIUM;

    /** One slicer run of the matrix. */
    public record Cell(FilamentType filament, PrintQuality quality, int copies) {

        String label() {
            return filament.name() + " " + quality.code() + " x" + copies;
        }
    }

    public static final List<Cell> MATRIX = matrix();

    private static List<Cell> matrix() {
        List<Cell> cells = new ArrayList<>();
        for (FilamentType filament : FilamentType.values()) {
            for (PrintQuality quality : PrintQuality.values()) {
                cells.add(new Cell(filament, quality, 1));
            }
        }
        for (int copies : BATCH_COPIES) {
            cells.add(new Cell(QuoteService.REFERENCE_FILAMENT, BATCH_QUALITY, copies));
        }
        return List.copyOf(cells);
    }

    private final CalibrationQueue queue;
    private final SliceCalibrationRepository rows;
    private final SlicerExecutionService slicer;
    private final SlicerProfileResolver profiles;
    private final SlicerCapacityGate gate;
    private final QuotePricingService pricing;
    private final Clock clock;

    public CalibrationRunner(CalibrationQueue queue, SliceCalibrationRepository rows, SlicerExecutionService slicer,
                             SlicerProfileResolver profiles, SlicerCapacityGate gate, QuotePricingService pricing,
                             Clock clock) {
        this.queue = queue;
        this.rows = rows;
        this.slicer = slicer;
        this.profiles = profiles;
        this.gate = gate;
        this.pricing = pricing;
        this.clock = clock;
    }

    /** Runs the whole matrix for one queued model and returns how many cells were measured. */
    public int calibrate(CalibrationQueueEntry entry) {
        entry.start(clock.instant());
        entry = queue.save(entry);

        Path workDir;
        try {
            workDir = Files.createTempDirectory("calibration-");
        } catch (IOException e) {
            finish(entry, 0, "Could not create a working directory: " + e.getMessage());
            return 0;
        }
        try {
            Path model = workDir.resolve("model" + entry.extension());
            try (InputStream in = queue.openModel(entry)) {
                Files.copy(in, model, StandardCopyOption.REPLACE_EXISTING);
            } catch (NoSuchFileException e) {
                String note = entry.getSource() == CalibrationSource.ORDER
                        ? "Model file is gone; order " + entry.getOrderNumber() + " was erased before its turn."
                        : "Model file is gone from the calibration directory.";
                log.warn("Calibration of {} skipped: {}", entry.getModelName(), note);
                finish(entry, 0, note);
                return 0;
            } catch (IOException | RuntimeException e) {
                log.warn("Calibration of {} skipped, its file could not be read: {}", entry.getModelName(), e.getMessage());
                finish(entry, 0, "Model file could not be read: " + e.getMessage());
                return 0;
            }

            long sizeBytes = Files.size(model);
            boolean threeMf = entry.extension().equals(".3mf");
            String version = slicer.slicerVersion().orElse(null);
            int written = 0;
            String lastFailure = null;
            for (int index = 0; index < MATRIX.size(); index++) {
                Cell cell = MATRIX.get(index);
                if (cell.copies() > 1 && threeMf) {
                    // The CLI does not clone 3MF input; the batch cells are not worth a slot.
                    log.info("Calibration cell {} of {} skipped: the slicer does not clone 3MF input",
                            cell.label(), entry.getModelName());
                    continue;
                }
                Path outputDir = Files.createDirectories(workDir.resolve("cell-" + index));
                try {
                    measure(entry, cell, model, outputDir, sizeBytes, version);
                    written++;
                } catch (RuntimeException e) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw e;
                    }
                    lastFailure = cell.label() + ": " + e.getMessage();
                    log.warn("Calibration cell {} of {} failed; the model continues: {}", cell.label(),
                            entry.getModelName(), e.getMessage());
                }
            }
            finish(entry, written, written == 0 ? "No cell could be sliced; last failure: " + lastFailure : null);
            log.info("Calibration of {} ({}) done: {} of {} cells measured", entry.getModelName(),
                    entry.getSource().code(), written, MATRIX.size());
            return written;
        } catch (IOException e) {
            finish(entry, 0, "Working directory failed: " + e.getMessage());
            return 0;
        } finally {
            deleteRecursively(workDir);
        }
    }

    private void measure(CalibrationQueueEntry entry, Cell cell, Path model, Path outputDir, long sizeBytes,
                         String version) {
        SliceProfile profile = profiles.resolve(cell.filament(), cell.quality());
        long[] startedNanos = new long[1];
        SliceQuoteResult slice = gate.run(() -> {
            startedNanos[0] = System.nanoTime();
            return cell.copies() == 1
                    ? slicer.executeSlicingQuote(model, outputDir, profile)
                    : slicer.executeBatchSlice(model, outputDir, profile, cell.copies());
        }, SLOT_WAIT_SECONDS);
        long durationMs = (System.nanoTime() - startedNanos[0]) / 1_000_000;

        double grams = pricing.gramsFromLength(slice.filamentLengthMm(), cell.filament());
        rows.save(SliceCalibration.measured(entry.getModelSha256(), entry.getModelName(), sizeBytes, entry.getSource(),
                entry.getOrderNumber(), cell.filament(), cell.quality(), cell.copies(), slice, grams, version,
                durationMs, clock.instant()));
    }

    private void finish(CalibrationQueueEntry entry, int written, String error) {
        entry.finish(clock.instant(), written, error);
        queue.save(entry);
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
