package com.slicercore.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/** The settings the checkout shows come from application.yml: one setup fee, CHF 3.00 (ADR 0011). */
class OrderSettingsBindingTest {

    @Configuration
    @EnableConfigurationProperties(OrderProperties.class)
    static class Config {
    }

    @Test
    void theShippedConfigurationPublishesOneSetupFeeOfThreeFrancs() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Config.class)
                .run(context -> {
                    OrderSettingsResponse response = OrderSettingsResponse.from(context.getBean(OrderProperties.class));

                    assertThat(response.setupFeeChf()).isEqualByComparingTo("3.00");
                });
    }
}
