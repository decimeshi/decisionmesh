package com.decisionmesh.persistence.converter;


import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JPA AttributeConverter for List<Map<String, Object>> ↔ JSONB text.
 *
 * Same root cause as StringListJsonConverter — Hibernate Reactive's
 * ReactiveJsonJdbcType uses Vert.x JsonObject which cannot bind JSON
 * arrays. Any List<T> field with @JdbcTypeCode(SqlTypes.JSON) will throw
 * DecodeException on INSERT. This converter bypasses that codec entirely.
 */
@Converter
public class MapListJsonConverter
        implements AttributeConverter<List<Map<String, Object>>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<Map<String, Object>>> LIST_TYPE =
            new TypeReference<>() {};

    @Override
    public String convertToDatabaseColumn(List<Map<String, Object>> list) {
        if (list == null || list.isEmpty()) {
            return "[]";
        }
        try {
            return MAPPER.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to serialize List<Map<String,Object>> to JSON", e);
        }
    }

    @Override
    public List<Map<String, Object>> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank() || json.equals("[]")) {
            return new ArrayList<>();
        }
        try {
            return MAPPER.readValue(json, LIST_TYPE);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    "Failed to deserialize JSON to List<Map<String,Object>>: " + json, e);
        }
    }
}