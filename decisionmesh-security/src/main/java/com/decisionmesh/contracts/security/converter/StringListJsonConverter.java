package com.decisionmesh.contracts.security.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.logging.Log;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;

/**
 * JPA AttributeConverter that serialises {@code List<String>} ↔ JSONB array string.
 *
 * <h3>Why @Convert instead of @JdbcTypeCode(SqlTypes.JSON)</h3>
 *
 * Hibernate Reactive's {@code ReactiveJsonJdbcType} routes every
 * {@code @JdbcTypeCode(SqlTypes.JSON)} field through
 * {@code new io.vertx.core.json.JsonObject(jsonString)} before binding.
 * {@code JsonObject} only handles JSON <em>objects</em> ({@code {...}}) —
 * passing a JSON <em>array</em> ({@code [...]}) throws:
 * <pre>
 * io.vertx.core.json.DecodeException: Failed to decode:
 *   Cannot deserialize value of type LinkedHashMap from Array value
 * </pre>
 * {@code @Convert} uses plain JDBC {@code String} binding, bypassing
 * {@code ReactiveJsonJdbcType} entirely.  Jackson handles serialisation
 * and the PostgreSQL {@code jsonb} operators used by {@code AdapterRegistry}
 * ({@code @>}, {@code = '[]'::jsonb}) work correctly on both sides.
 */
@Converter
public class StringListJsonConverter implements AttributeConverter<List<String>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};

    /**
     * {@code List<String>} → JSON array string stored in the {@code jsonb} column.
     *
     * <p>Both {@code null} and empty list produce {@code "[]"}.
     * An empty array is the sentinel value meaning "eligible for all intent types"
     * in the {@code AdapterRegistry} WHERE clause.
     */
    @Override
    public String convertToDatabaseColumn(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            Log.errorf(e, "StringListJsonConverter: failed to serialize %s", list);
            throw new IllegalArgumentException("Failed to serialize List<String> to JSON", e);
        }
    }

    /**
     * JSON array string from the {@code jsonb} column → {@code List<String>}.
     *
     * <p>Returns an empty list for:
     * <ul>
     *   <li>{@code null} — SQL NULL column value</li>
     *   <li>blank string — should not occur but defensive</li>
     *   <li>{@code "[]"} — fast-path for the common empty-array case</li>
     *   <li>{@code "null"} — JSON null literal; PostgreSQL can return this
     *       when a {@code jsonb} column stores the JSON value {@code null}
     *       (distinct from SQL NULL). Without this guard Jackson would throw
     *       {@code MismatchedInputException} trying to read null as a List.</li>
     * </ul>
     */
    @Override
    public List<String> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank() || json.equals("[]") || json.equals("null")) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            Log.errorf(e, "StringListJsonConverter: failed to deserialize '%s'", json);
            throw new IllegalArgumentException(
                    "Failed to deserialize JSON to List<String>: " + json, e);
        }
    }
}