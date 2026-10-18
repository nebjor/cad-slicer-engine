package com.slicercore.model;

/**
 * A filament colour a customer can order.
 *
 * @param code  stable identifier sent by clients, e.g. {@code black}
 * @param label display name
 * @param hex   CSS colour for swatches and the 3D preview, e.g. {@code #1f1f1f}
 */
public record ColorOption(String code, String label, String hex) {
}
