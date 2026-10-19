package com.slicercore.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Cross-cutting beans. Configuration properties are registered by
 * {@code @ConfigurationPropertiesScan} on the application class, so each module owns its own
 * settings and this package depends on none of them.
 */
@Configuration
public class QuoteConfig {

    /** Injected wherever time matters so tests can control it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
