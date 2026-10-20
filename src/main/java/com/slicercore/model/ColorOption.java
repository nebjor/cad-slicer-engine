package com.slicercore.model;

/**
 * A filament colour a customer can order.
 *
 * @param code          stable identifier sent by clients, e.g. {@code black}
 * @param label         display name
 * @param hex           CSS colour for swatches and the 3D preview, e.g. {@code #1f1f1f}
 * @param pricePerKgChf material price of this colour; in the catalogue configuration it may be
 *                      left out, then the filament's price applies and the catalogue fills it in
 */
public record ColorOption(String code, String label, String hex, Double pricePerKgChf) {
}
