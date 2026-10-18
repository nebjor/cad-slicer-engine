package com.slicercore.service;

import com.slicercore.config.FilamentCatalogProperties;
import com.slicercore.model.ColorOption;
import com.slicercore.model.FilamentType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FilamentCatalogTest {

    private static final ColorOption BLACK = new ColorOption("black", "Black", "#1f1f1f");
    private static final ColorOption RED = new ColorOption("red", "Red", "#d62828");

    private final FilamentCatalog catalog = new FilamentCatalog(new FilamentCatalogProperties(Map.of(
            FilamentType.PLA, List.of(BLACK, RED),
            FilamentType.PETG, List.of(BLACK))));

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
    void emptyCatalogFailsLoudly() {
        FilamentCatalog empty = new FilamentCatalog(new FilamentCatalogProperties(Map.of()));

        assertThat(empty.colorsFor(FilamentType.PLA)).isEmpty();
        assertThatThrownBy(() -> empty.resolve(FilamentType.PLA, "black"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No colours");
    }
}
