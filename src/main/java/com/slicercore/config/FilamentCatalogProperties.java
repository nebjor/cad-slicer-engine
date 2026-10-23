package com.slicercore.config;

import com.slicercore.quote.ColorOption;
import com.slicercore.slicer.FilamentType;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * What the print farm currently stocks, bound from {@code catalog.*}. This is the single
 * place a back-office will later edit; until then it lives in application.yml.
 *
 * @param colors colours available per filament type, in display order
 */
@ConfigurationProperties(prefix = "catalog")
public record FilamentCatalogProperties(Map<FilamentType, List<ColorOption>> colors) {
}
