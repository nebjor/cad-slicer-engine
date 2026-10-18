package com.slicercore.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Filaments that can be quoted. Each value maps to a Bambu Studio filament profile that is
 * shipped inside the AppImage and to the material density used to derive mass from the
 * extruded filament length (the slicer's own gram figure is unreliable, see
 * docs/feasibility/bambu-studio-cli-headless.md O3).
 */
public enum FilamentType {
    PLA("Bambu PLA Basic @BBL A1", 1.24),
    PETG("Bambu PETG Basic @BBL A1", 1.27);

    private final String profileName;
    private final double densityGramsPerCm3;

    FilamentType(String profileName, double densityGramsPerCm3) {
        this.profileName = profileName;
        this.densityGramsPerCm3 = densityGramsPerCm3;
    }

    public String profileName() {
        return profileName;
    }

    public double densityGramsPerCm3() {
        return densityGramsPerCm3;
    }

    public static FilamentType fromInput(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Filament is required. Supported: " + supportedValues());
        }
        return Arrays.stream(values())
                .filter(value -> value.name().equalsIgnoreCase(raw.trim()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported filament '" + raw + "'. Supported: " + supportedValues()));
    }

    public static String supportedValues() {
        return Arrays.stream(values()).map(Enum::name).collect(Collectors.joining(", "));
    }
}
