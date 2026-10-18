package com.slicercore.order;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Commercial settings for orders, bound from {@code orders.*}.
 *
 * @param shippingFlatChf    flat shipping charge per order
 * @param vatEnabled         false while the business is not VAT registered; prices then carry no VAT
 * @param vatRate            Swiss standard rate, applied inclusively when enabled
 * @param quoteValidityDays  how long the price of a placed order stays payable
 * @param retentionDays      days after shipping until model and personal data are deleted
 * @param filesDir           directory (a Docker volume in production) holding order model files
 * @param shipToCountries    ISO 3166-1 alpha-2 codes accepted at checkout
 */
@ConfigurationProperties(prefix = "orders")
public record OrderProperties(
        BigDecimal shippingFlatChf,
        boolean vatEnabled,
        BigDecimal vatRate,
        int quoteValidityDays,
        int retentionDays,
        String filesDir,
        Set<String> shipToCountries) {
}
