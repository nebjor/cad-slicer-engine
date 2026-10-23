package com.slicercore.slicer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The warning sources and the shaping rules: deduplicated, bounded in count and length. */
class SlicerWarningsTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void findsWarningFieldsWhereverTheyAreInResultJson() {
        var root = json.readTree("""
                {"return_code": 0, "error_string": "Success.",
                 "warnings": ["Object a is outside the plate", {"message": "Object b overlaps"}],
                 "sliced_plates": [
                   {"warning_message": "Plate 1 has an empty layer", "objects": [{"name": "a", "warning": ""}]},
                   {"warning_message": "", "objects": []}
                 ]}
                """);

        // Empty warning fields, as an untroubled plate or object would carry, are not warnings.
        assertThat(SlicerWarnings.fromResultJson(root)).containsExactly(
                "Object a is outside the plate", "Object b overlaps", "Plate 1 has an empty layer");
    }

    @Test
    void anUnusualErrorStringOnASuccessfulRunIsAWarning() {
        assertThat(SlicerWarnings.fromResultJson(json.readTree("{\"return_code\": 0, \"error_string\": \"Sliced with repairs.\"}")))
                .containsExactly("Sliced with repairs.");
        assertThat(SlicerWarnings.fromResultJson(json.readTree("{\"return_code\": 0, \"error_string\": \"Success.\"}")))
                .isEmpty();
        // A failed run is rejected elsewhere; its error is not a warning.
        assertThat(SlicerWarnings.fromResultJson(json.readTree("{\"return_code\": -2, \"error_string\": \"Invalid.\"}")))
                .isEmpty();
    }

    @Test
    void consoleLinesCountOnlyWhenTheyStartWithWarning() {
        assertThat(SlicerWarnings.fromOutputLine("warning: object too small")).contains("object too small");
        assertThat(SlicerWarnings.fromOutputLine("  Warning - thin wall at layer 3")).contains("thin wall at layer 3");
        assertThat(SlicerWarnings.fromOutputLine("[warning] mesh repaired")).contains("mesh repaired");
        assertThat(SlicerWarnings.fromOutputLine("[error] glfwInit return error, code 65544")).isEmpty();
        assertThat(SlicerWarnings.fromOutputLine("got a warning while slicing")).isEmpty();
        assertThat(SlicerWarnings.fromOutputLine("warning:")).isEmpty();
    }

    @Test
    void normalizesToUniqueTrimmedBoundedLines() {
        String longLine = "x".repeat(250);
        List<String> many = IntStream.rangeClosed(1, 12).mapToObj(i -> "warning " + i).toList();

        List<String> shaped = SlicerWarnings.normalize(
                List.of("  thin wall ", "", "thin wall", longLine, "second"),
                many);

        assertThat(shaped).hasSize(SlicerWarnings.MAX_WARNINGS);
        assertThat(shaped.subList(0, 4)).containsExactly("thin wall", "x".repeat(197) + "...", "second", "warning 1");
        assertThat(shaped).allSatisfy(line -> assertThat(line.length()).isLessThanOrEqualTo(SlicerWarnings.MAX_LENGTH));
        assertThat(SlicerWarnings.normalize(List.of(), List.of("  "))).isEmpty();
    }
}
