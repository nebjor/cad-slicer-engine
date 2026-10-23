package com.slicercore.quote;

import com.slicercore.catalog.ColorOption;
import com.slicercore.catalog.FilamentType;
import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.config.QuotePricingProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Answers which colours a filament is stocked in, at what material price, and validates client
 * selections. A colour without a price of its own costs what the filament costs, so the
 * catalogue only lists exceptions such as silk being dearer than matte.
 */
@Component
public class FilamentCatalog {

    private final FilamentCatalogProperties properties;
    private final QuotePricingProperties pricing;

    public FilamentCatalog(FilamentCatalogProperties properties, QuotePricingProperties pricing) {
        this.properties = properties;
        this.pricing = pricing;
    }

    /** Stocked colours in display order, every one with its effective price per kilogram. */
    public List<ColorOption> colorsFor(FilamentType filament) {
        List<ColorOption> colors = properties.colors() == null ? null : properties.colors().get(filament);
        if (colors == null) {
            return List.of();
        }
        double filamentPrice = pricing.materialPriceChfPerKg().getOrDefault(filament, 0.0);
        return colors.stream()
                .map(color -> color.pricePerKgChf() == null
                        ? new ColorOption(color.code(), color.label(), color.hex(), filamentPrice)
                        : color)
                .toList();
    }

    /**
     * Resolves a colour code for the filament. A blank code selects the first stocked colour so
     * older clients that do not send one keep working.
     */
    public ColorOption resolve(FilamentType filament, String colorCode) {
        List<ColorOption> colors = colorsFor(filament);
        if (colors.isEmpty()) {
            throw new IllegalArgumentException("No colours are stocked for " + filament + ".");
        }
        if (colorCode == null || colorCode.isBlank()) {
            return colors.getFirst();
        }

        String wanted = colorCode.trim();
        return colors.stream()
                .filter(color -> color.code().equalsIgnoreCase(wanted))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Colour '" + colorCode + "' is not available for " + filament + ". Available: "
                                + colors.stream().map(ColorOption::code).collect(Collectors.joining(", "))));
    }
}
