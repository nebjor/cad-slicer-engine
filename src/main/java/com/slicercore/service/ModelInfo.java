package com.slicercore.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What {@code bambu-studio-cli --info} printed about a model: one block of {@code key = value}
 * lines per object, headed by the object's file name in brackets. The verified sample
 * (feasibility note, section 1.2) reads {@code manifold = yes}, {@code number_of_facets = 4},
 * {@code volume = 166.666672}; a mesh that needed repair adds counters such as
 * {@code open_edges} or {@code facets_reversed}.
 *
 * @param manifold  true when every object is manifold, false when at least one is not, null
 *                  when the output carried no manifold line at all
 * @param facets    facets summed over all objects
 * @param volumeMm3 volume summed over all objects
 * @param warnings  one line summarising the mesh error counters, if any were printed
 */
record ModelInfo(Boolean manifold, long facets, double volumeMm3, List<String> warnings) {

    /** The inspection did not run or failed; nothing is known about the mesh. */
    static final ModelInfo UNAVAILABLE = new ModelInfo(null, 0, 0, List.of());

    private static final Pattern KEY_VALUE = Pattern.compile("^\\s*([a-z_]+)\\s*=\\s*(\\S.*?)\\s*$");
    private static final List<String> MESH_ERROR_KEYS = List.of(
            "open_edges", "degenerate_facets", "edges_fixed", "facets_removed", "facets_reversed", "backwards_edges");

    static ModelInfo parse(List<String> lines) {
        Boolean manifold = null;
        long facets = 0;
        double volume = 0;
        Map<String, Long> meshErrors = new LinkedHashMap<>();

        for (String line : lines) {
            Matcher matcher = KEY_VALUE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String key = matcher.group(1);
            String value = matcher.group(2);
            switch (key) {
                case "manifold" -> manifold = (manifold == null || manifold) && "yes".equalsIgnoreCase(value);
                case "number_of_facets" -> facets += parseLong(value);
                case "volume" -> volume += parseDouble(value);
                default -> {
                    if (MESH_ERROR_KEYS.contains(key)) {
                        meshErrors.merge(key, parseLong(value), Long::sum);
                    }
                }
            }
        }

        List<String> counters = new ArrayList<>();
        for (String key : MESH_ERROR_KEYS) {
            Long count = meshErrors.get(key);
            if (count != null && count > 0) {
                counters.add(key + " = " + count);
            }
        }
        List<String> warnings = counters.isEmpty()
                ? List.of()
                : List.of("Mesh errors reported by the slicer: " + String.join(", ", counters));
        return new ModelInfo(manifold, facets, volume, warnings);
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
