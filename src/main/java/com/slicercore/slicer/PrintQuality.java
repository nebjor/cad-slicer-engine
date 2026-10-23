package com.slicercore.slicer;

import com.slicercore.slicer.PrintQuality;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Print quality tiers exposed to customers. Each tier maps to a Bambu Studio process profile
 * for the configured machine and to a base lead time. The label is what the shop shows; the
 * frontend renders the catalogue as published and holds no list of its own.
 */
public enum PrintQuality {
    COARSE("coarse", "Grob", "0.24mm Draft @BBL A1", 0.24, 2),
    MEDIUM("medium", "Standard", "0.20mm Standard @BBL A1", 0.20, 3),
    HIGH("high", "Fein", "0.12mm Fine @BBL A1", 0.12, 4);

    private final String code;
    private final String label;
    private final String processProfileName;
    private final double layerHeightMm;
    private final int baseLeadTimeDays;

    PrintQuality(String code, String label, String processProfileName, double layerHeightMm, int baseLeadTimeDays) {
        this.code = code;
        this.label = label;
        this.processProfileName = processProfileName;
        this.layerHeightMm = layerHeightMm;
        this.baseLeadTimeDays = baseLeadTimeDays;
    }

    public String code() {
        return code;
    }

    public String label() {
        return label;
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
