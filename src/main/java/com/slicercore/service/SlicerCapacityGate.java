package com.slicercore.service;

import com.slicercore.config.SlicerCapacityProperties;
import com.slicercore.model.SlicerBusyException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Semaphore around slicer runs. Every run is a CPU-bound native process, so without this an
 * attacker, or simply a busy afternoon, could start an unbounded number of them and take the
 * host down.
 */
@Component
public class SlicerCapacityGate {

    private static final Logger log = LoggerFactory.getLogger(SlicerCapacityGate.class);

    private final SlicerCapacityProperties properties;
    private final Semaphore slots;

    public SlicerCapacityGate(SlicerCapacityProperties properties) {
        this.properties = properties;
        this.slots = new Semaphore(Math.max(1, properties.maxConcurrent()), true);
    }

    /** Runs the task once a slot is free, or rejects it after the configured wait. */
    public <T> T run(SlicerTask<T> task) {
        boolean acquired;
        try {
            acquired = slots.tryAcquire(properties.queueWaitSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SlicerBusyException("Slicing was interrupted while waiting for a slot.", 5);
        }

        if (!acquired) {
            log.warn("No slicer slot free within {} s ({} concurrent allowed)",
                    properties.queueWaitSeconds(), properties.maxConcurrent());
            throw new SlicerBusyException(
                    "The slicer is busy right now. Please try again in a few seconds.",
                    properties.queueWaitSeconds());
        }

        try {
            return task.run();
        } finally {
            slots.release();
        }
    }

    public int availableSlots() {
        return slots.availablePermits();
    }

    @FunctionalInterface
    public interface SlicerTask<T> {
        T run();
    }
}
