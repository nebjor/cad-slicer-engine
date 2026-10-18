package com.slicercore.model;

import java.util.List;

/** Selectable quote parameters, so the frontend can render exactly what the backend supports. */
public record QuoteOptions(List<FilamentOption> filaments, List<QualityOption> qualities, int maxQuantity) {

    public record FilamentOption(String code, String label, String profileName, List<ColorOption> colors) {
    }

    public record QualityOption(String code, String label, double layerHeightMm, int baseLeadTimeDays) {
    }
}
