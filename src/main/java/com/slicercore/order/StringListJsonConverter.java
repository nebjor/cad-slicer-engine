package com.slicercore.order;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Stores a list of short strings as a JSON array in a text column, for example the slicer
 * warnings of an order item. A plain text column with JSON inside keeps the driver out of it
 * (PostgreSQL refuses a {@code varchar} parameter for a {@code jsonb} column unless the JDBC
 * URL is changed) and the value is never queried by content; the database default
 * {@code '[]'} covers rows written before the column existed.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final Logger log = LoggerFactory.getLogger(StringListJsonConverter.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<String> attribute) {
        return MAPPER.writeValueAsString(attribute == null ? List.of() : attribute);
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        try {
            List<String> values = MAPPER.readValue(column, STRING_LIST);
            return values == null ? List.of() : List.copyOf(values);
        } catch (RuntimeException e) {
            log.warn("Ignoring an unreadable string list in the database: {}", e.getMessage());
            return List.of();
        }
    }
}
