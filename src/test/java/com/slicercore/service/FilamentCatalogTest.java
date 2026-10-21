package com.slicercore.service;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.config.QuotePricingProperties;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilamentCatalogTest {

    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f", 36.0);
    private static final ColorOption RED = new ColorOption("red", "Red", "#d62828", 36.0);

    private static final QuotePricingProperties PRICING = new QuotePricingProperties("CHF", 5, 4, 3, 16, 1.75, 100, 300, new QuotePricingProperties.Plate(256, 256, 10, 15),
            Map.of(FilamentType.PLA, 36.0, FilamentType.PETG, 45.0), QuotePricingProperties.Material.UNSCALED);

    private final FilamentCatalog catalog = new FilamentCatalog(new FilamentCatalogProperties(Map.of(
            FilamentType.PLA, List.of(BLACK, RED),
            FilamentType.PETG, List.of(BLACK))), PRICING);

    @Test
    void listsStockedColoursPerFilament() {
        assertThat(catalog.colorsFor(FilamentType.PLA)).containsExactly(BLACK, RED);
        assertThat(catalog.colorsFor(FilamentType.PETG)).containsExactly(BLACK);
    }

    @Test
    void resolvesCodesCaseInsensitivelyAndDefaultsToTheFirstColour() {
        assertThat(catalog.resolve(FilamentType.PLA, " Red ")).isEqualTo(RED);
        assertThat(catalog.resolve(FilamentType.PLA, null)).isEqualTo(BLACK);
        assertThat(catalog.resolve(FilamentType.PLA, "")).isEqualTo(BLACK);
    }

    @Test
    void rejectsColoursNotStockedForTheFilament() {
        assertThatThrownBy(() -> catalog.resolve(FilamentType.PETG, "red"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("'red' is not available for PETG")
                .hasMessageContaining("Available: black");
    }

    @Test
    void coloursWithoutAPriceOfTheirOwnCostWhatTheFilamentCosts() {
        FilamentCatalog priced = new FilamentCatalog(new FilamentCatalogProperties(Map.of(
                FilamentType.PLA, List.of(
                        new ColorOption("black", "Black", "#1f1f1f", null),
                        new ColorOption("silk", "Silk", "#c94fb4", 48.0)))), PRICING);

        assertThat(priced.colorsFor(FilamentType.PLA)).extracting(ColorOption::pricePerKgChf).containsExactly(36.0, 48.0);
        assertThat(priced.resolve(FilamentType.PLA, "black").pricePerKgChf()).isEqualTo(36.0);
        assertThat(priced.resolve(FilamentType.PLA, "silk").pricePerKgChf()).isEqualTo(48.0);
    }

    @Test
    void emptyCatalogFailsLoudly() {
        FilamentCatalog empty = new FilamentCatalog(new FilamentCatalogProperties(Map.of()), PRICING);

        assertThat(empty.colorsFor(FilamentType.PLA)).isEmpty();
        assertThatThrownBy(() -> empty.resolve(FilamentType.PLA, "black"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No colours");
    }
}
