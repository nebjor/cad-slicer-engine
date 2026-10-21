package com.slicercore.calibration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the nightly slice calibration (ADR 0008), bound from {@code calibration.*}.
 * The cron expression itself ({@code calibration.cron}) is read by the scheduler annotation.
 *
 * @param enabled           false switches off both the nightly pass and the order intake;
 *                          the admin endpoints keep answering
 * @param maxModelsPerNight queued models one pass works through, oldest first; each costs
 *                          eleven slicer runs
 * @param dir               where models queued by hand from the upload store are copied to,
 *                          so their expiry does not lose them before the night
 */
@ConfigurationProperties(prefix = "calibration")
public record CalibrationProperties(boolean enabled, int maxModelsPerNight, String dir) {
}
