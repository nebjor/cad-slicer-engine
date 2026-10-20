package com.slicercore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where and how long uploaded models are kept between quotes, bound from
 * {@code quote.model-store.*}.
 *
 * @param dir          directory for the files; blank means a fresh directory under the JVM's temp dir
 * @param ttlMinutes   how long a model stays available for re-quotes after its last upload
 * @param maxMegabytes size cap for the whole store; the least recently used models make room
 */
@ConfigurationProperties(prefix = "quote.model-store")
public record ModelStoreProperties(String dir, int ttlMinutes, long maxMegabytes) {
}
