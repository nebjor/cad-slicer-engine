package com.slicercore.config;

import com.slicercore.order.OrderProperties;
import com.slicercore.payment.PaymentProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
@EnableConfigurationProperties({
        QuotePricingProperties.class,
        RateLimitProperties.class,
        SlicerCapacityProperties.class,
        FilamentCatalogProperties.class,
        OrderProperties.class,
        PaymentProperties.class
})
public class QuoteConfig {

    /** Injected wherever time matters so tests can control it. */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
