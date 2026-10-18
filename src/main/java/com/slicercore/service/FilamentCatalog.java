package com.slicercore.service;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/** Answers which colours a filament is stocked in and validates client selections. */
@Component
public class FilamentCatalog {

    private final FilamentCatalogProperties properties;

    public FilamentCatalog(FilamentCatalogProperties properties) {
        this.properties = properties;
    }

    public List<ColorOption> colorsFor(FilamentType filament) {
        List<ColorOption> colors = properties.colors() == null ? null : properties.colors().get(filament);
        return colors == null ? List.of() : colors;
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
