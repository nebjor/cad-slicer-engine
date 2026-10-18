package com.slicercore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bounds how many slicer processes run at once, bound from {@code slicer.capacity.*}. Each run
 * is CPU bound, so this is the main guard against a burst of uploads exhausting the host.
 *
 * @param maxConcurrent    slicer processes allowed to run in parallel
 * @param queueWaitSeconds how long a request waits for a free slot before it is rejected
 */
@ConfigurationProperties(prefix = "slicer.capacity")
public record SlicerCapacityProperties(int maxConcurrent, int queueWaitSeconds) {
}
