package com.slicercore.model;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Print quality tiers exposed to customers. Each tier maps to a Bambu Studio process profile
 * for the configured machine and to a base lead time.
 */
public enum PrintQuality {
    COARSE("coarse", "0.24mm Draft @BBL A1", 0.24, 2),
    MEDIUM("medium", "0.20mm Standard @BBL A1", 0.20, 3),
    HIGH("high", "0.12mm Fine @BBL A1", 0.12, 4);

    private final String code;
    private final String processProfileName;
    private final double layerHeightMm;
    private final int baseLeadTimeDays;

    PrintQuality(String code, String processProfileName, double layerHeightMm, int baseLeadTimeDays) {
        this.code = code;
        this.processProfileName = processProfileName;
        this.layerHeightMm = layerHeightMm;
        this.baseLeadTimeDays = baseLeadTimeDays;
    }

    public String code() {
        return code;
    }

    public String processProfileName() {
        return processProfileName;
    }

    public double layerHeightMm() {
        return layerHeightMm;
    }

    public int baseLeadTimeDays() {
        return baseLeadTimeDays;
    }

    public static PrintQuality fromInput(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Quality is required. Supported: " + supportedValues());
        }
        String normalized = raw.trim();
        return Arrays.stream(values())
                .filter(value -> value.code.equalsIgnoreCase(normalized) || value.name().equalsIgnoreCase(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unsupported quality '" + raw + "'. Supported: " + supportedValues()));
    }

    public static String supportedValues() {
        return Arrays.stream(values()).map(PrintQuality::code).collect(Collectors.joining(", "));
    }
}
