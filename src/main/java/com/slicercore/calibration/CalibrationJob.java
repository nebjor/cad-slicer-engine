package com.slicercore.calibration;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One calibration pass a night: up to {@code calibration.max-models-per-night} queued models,
 * oldest first, each through the whole matrix. The owner can start the same pass at any
 * time from the admin API; it then runs in the background. Two passes never overlap.
 */
@Component
public class CalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(CalibrationJob.class);

    private final CalibrationProperties properties;
    private final CalibrationQueue queue;
    private final CalibrationRunner runner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("calibration").factory());
    private final AtomicBoolean running = new AtomicBoolean();

    public CalibrationJob(CalibrationProperties properties, CalibrationQueue queue, CalibrationRunner runner) {
        this.properties = properties;
        this.queue = queue;
        this.runner = runner;
    }

    @Scheduled(cron = "${calibration.cron:0 0 3 * * *}")
    public void nightly() {
        if (!properties.enabled()) {
            return;
        }
        runPass();
    }

    /** One pass, now, on this thread. Returns how many models were worked through. */
    public int runPass() {
        if (!running.compareAndSet(false, true)) {
            log.info("Calibration pass skipped: one is already running");
            return 0;
        }
        try {
            return pass();
        } finally {
            running.set(false);
        }
    }

    /** One pass in the background; false when a pass is already running. */
    public boolean runPassAsync() {
        if (!running.compareAndSet(false, true)) {
            return false;
        }
        worker.submit(() -> {
            try {
                pass();
            } catch (RuntimeException e) {
                log.error("Calibration pass failed: {}", e.getMessage(), e);
            } finally {
                running.set(false);
            }
        });
        return true;
    }

    public boolean isRunning() {
        return running.get();
    }

    private int pass() {
        List<CalibrationQueueEntry> batch = queue.nextBatch(properties.maxModelsPerNight());
        if (batch.isEmpty()) {
            log.debug("Calibration pass: nothing queued");
            return 0;
        }
        log.info("Calibration pass: {} model(s) to measure", batch.size());
        int measured = 0;
        for (CalibrationQueueEntry entry : batch) {
            try {
                runner.calibrate(entry);
                measured++;
            } catch (RuntimeException e) {
                if (Thread.currentThread().isInterrupted()) {
                    log.warn("Calibration pass interrupted after {} model(s)", measured);
                    return measured;
                }
                log.error("Calibration of {} failed: {}", entry.getModelName(), e.getMessage(), e);
            }
        }
        return measured;
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}
