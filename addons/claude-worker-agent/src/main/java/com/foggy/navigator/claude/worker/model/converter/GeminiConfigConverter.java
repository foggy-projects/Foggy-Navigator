package com.foggy.navigator.claude.worker.model.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.model.GeminiConfig;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * GeminiConfig <-> JSON 转换器
 */
@Converter
public class GeminiConfigConverter implements AttributeConverter<GeminiConfig, String> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(GeminiConfig attribute) {
        if (attribute == null) return null;
        try {
            return OBJECT_MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize GeminiConfig", e);
        }
    }

    @Override
    public GeminiConfig convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return OBJECT_MAPPER.readValue(dbData, GeminiConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize GeminiConfig", e);
        }
    }
}
