package com.slicercore.calibration;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One calibration pass over the manual queue, started by the owner from the admin API and
 * never by a schedule (ADR 0008): everything waiting, oldest first, each model through the
 * whole matrix. The pass runs in the background; two passes never overlap.
 */
@Component
public class CalibrationJob {

    private static final Logger log = LoggerFactory.getLogger(CalibrationJob.class);
    /** A pass takes what is waiting when it starts, up to this many models. */
    static final int MAX_MODELS_PER_PASS = CalibrationQueue.MAX_LIST_SIZE;

    private final CalibrationQueue queue;
    private final CalibrationRunner runner;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("calibration").factory());
    private final AtomicBoolean running = new AtomicBoolean();

    public CalibrationJob(CalibrationQueue queue, CalibrationRunner runner) {
        this.queue = queue;
        this.runner = runner;
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
        List<CalibrationQueueEntry> batch = queue.nextBatch(MAX_MODELS_PER_PASS);
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
