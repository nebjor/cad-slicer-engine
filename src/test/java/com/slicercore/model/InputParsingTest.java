package com.slicercore.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Request parameters arrive as free text; the enums must accept the documented spellings only. */
class InputParsingTest {

    @ParameterizedTest
    @ValueSource(strings = {"PLA", "pla", " Pla "})
    void filamentIsParsedCaseInsensitively(String raw) {
        assertThat(FilamentType.fromInput(raw)).isEqualTo(FilamentType.PLA);
    }

    @Test
    void unsupportedFilamentListsTheSupportedOnes() {
        assertThatThrownBy(() -> FilamentType.fromInput("ABS"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ABS")
                .hasMessageContaining("PLA, PETG, PCTG");
        assertThatThrownBy(() -> FilamentType.fromInput(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @ParameterizedTest
    @ValueSource(strings = {"high", "HIGH", "High "})
    void qualityAcceptsCodeAndEnumName(String raw) {
        assertThat(PrintQuality.fromInput(raw)).isEqualTo(PrintQuality.HIGH);
    }

    @Test
    void qualityTiersMapToProfilesAndLayerHeights() {
        assertThat(PrintQuality.COARSE.layerHeightMm()).isEqualTo(0.24);
        assertThat(PrintQuality.MEDIUM.processProfileName()).isEqualTo("0.20mm Standard @BBL A1");
        assertThat(PrintQuality.HIGH.baseLeadTimeDays()).isEqualTo(4);
        assertThatThrownBy(() -> PrintQuality.fromInput("ultra"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("coarse, medium, high");
    }
}
