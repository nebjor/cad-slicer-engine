package com.slicercore.calibration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the nightly slice calibration (ADR 0008), bound from {@code calibration.*}.
 * The cron expression itself ({@code calibration.cron}) is read by the scheduler annotation.
 *
 * @param enabled           true lets the nightly pass work through what the owner queued;
 *                          false (the default) leaves the queue alone until {@code run-now}.
 *                          Recording the quote path's own slices does not depend on it
 * @param maxModelsPerNight queued models one pass works through, oldest first; each costs
 *                          eleven slicer runs
 * @param dir               where models queued by hand from the upload store are copied to,
 *                          so their expiry does not lose them before the night
 */
@ConfigurationProperties(prefix = "calibration")
public record CalibrationProperties(boolean enabled, int maxModelsPerNight, String dir) {
}
