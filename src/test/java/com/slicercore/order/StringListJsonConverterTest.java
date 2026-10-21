package com.slicercore.order;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The column format of the slicer warnings: a JSON array in a text column. */
class StringListJsonConverterTest {

    private final StringListJsonConverter converter = new StringListJsonConverter();

    @Test
    void roundTripsAsAJsonArray() {
        List<String> warnings = List.of("Object \"vase\" has thin walls", "Mesh errors: open_edges = 4, facets_reversed = 2");

        String column = converter.convertToDatabaseColumn(warnings);

        assertThat(column).startsWith("[").endsWith("]").contains("\\\"vase\\\"");
        assertThat(converter.convertToEntityAttribute(column)).isEqualTo(warnings);
    }

    @Test
    void nothingIsAnEmptyArrayInBothDirections() {
        assertThat(converter.convertToDatabaseColumn(List.of())).isEqualTo("[]");
        assertThat(converter.convertToDatabaseColumn(null)).isEqualTo("[]");
        assertThat(converter.convertToEntityAttribute("[]")).isEmpty();
        assertThat(converter.convertToEntityAttribute(null)).isEmpty();
        assertThat(converter.convertToEntityAttribute(" ")).isEmpty();
    }

    @Test
    void anUnreadableColumnDoesNotBreakTheOrder() {
        assertThat(converter.convertToEntityAttribute("not json")).isEmpty();
    }
}
