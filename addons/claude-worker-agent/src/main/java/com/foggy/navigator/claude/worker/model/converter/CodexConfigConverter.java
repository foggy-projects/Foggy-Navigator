package com.foggy.navigator.claude.worker.model.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.common.model.CodexConfig;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

/**
 * JPA AttributeConverter：CodexConfig ⇔ JSON 字符串
 * <p>
 * 使用 TEXT 列以兼容 H2 测试数据库。
 */
@Converter
public class CodexConfigConverter implements AttributeConverter<CodexConfig, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public String convertToDatabaseColumn(CodexConfig attribute) {
        if (attribute == null) return null;
        try {
            return MAPPER.writeValueAsString(attribute);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialize CodexConfig to JSON", e);
        }
    }

    @Override
    public CodexConfig convertToEntityAttribute(String dbData) {
        if (dbData == null || dbData.isBlank()) return null;
        try {
            return MAPPER.readValue(dbData, CodexConfig.class);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to deserialize CodexConfig from JSON", e);
        }
    }
}
