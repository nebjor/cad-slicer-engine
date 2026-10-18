package com.slicercore.order;

import java.math.BigDecimal;
import java.util.List;

/** What the checkout page needs to show before an order exists. */
public record OrderSettingsResponse(
        String currency,
        BigDecimal shippingFlatChf,
        boolean vatEnabled,
        BigDecimal vatRate,
        int quoteValidityDays,
        List<String> shipToCountries) {

    public static OrderSettingsResponse from(OrderProperties settings) {
        return new OrderSettingsResponse("CHF", Chf.round(settings.shippingFlatChf()), settings.vatEnabled(),
                settings.vatRate(), settings.quoteValidityDays(), settings.shipToCountries().stream().sorted().toList());
    }
}
