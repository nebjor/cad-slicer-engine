package com.slicercore.calibration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the slice calibration (ADR 0008), bound from {@code calibration.*}. There is
 * no schedule to configure: the queue is worked through only when the owner asks.
 *
 * @param dir where models queued by hand from the upload store are copied to, so their
 *            expiry does not lose them before the owner starts a pass
 */
@ConfigurationProperties(prefix = "calibration")
public record CalibrationProperties(String dir) {
}
