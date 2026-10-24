package com.slicercore.catalog;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Filaments that can be quoted. Each value maps to the Bambu Studio filament profile of the
 * brand actually in stock (shipped inside the AppImage) and to the material density used to
 * derive mass from the extruded filament length (the slicer's own gram figure is unreliable,
 * see docs/feasibility/bambu-studio-cli-headless.md O3).
 *
 * A quote slices only with the PLA profile and scales the result for the other filaments by
 * measured factors (ADR 0007); their profiles remain the ones to re-measure those factors with.
 * The stocked colours per type live in the catalogue configuration, not here.
 */
public enum FilamentType {
    /** Polymaker PolyTerra (matte) and Bambu PLA Basic / Silk; one profile prices them all. */
    PLA("PLA", "Bambu PLA Basic @BBL A1", 1.24),
    /** Polymaker PolyLite PETG. */
    PETG("PETG", "PolyLite PETG @BBL A1", 1.27),
    /** Fiberlogy PCTG transparent; tougher than PETG, printed with the generic PCTG profile. */
    PCTG("PCTG (transparent)", "Generic PCTG @BBL A1", 1.23);

    private final String label;
    private final String profileName;
    private final double densityGramsPerCm3;

    FilamentType(String label, String profileName, double densityGramsPerCm3) {
        this.label = label;
        this.profileName = profileName;
        this.densityGramsPerCm3 = densityGramsPerCm3;
    }

    public String label() {
        return label;
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
