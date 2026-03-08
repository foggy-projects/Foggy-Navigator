package com.foggy.navigator.agent.framework.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.Module;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Jackson 全局配置：LocalDateTime 序列化带时区偏移
 * <p>
 * 序列化输出示例：2026-03-05T09:13:00+08:00
 * 反序列化兼容：带时区偏移和不带时区两种格式
 * <p>
 * 通过注册 Jackson Module Bean，Spring Boot 自动将其注入到全局 ObjectMapper
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Module localDateTimeTimezoneModule() {
        SimpleModule module = new SimpleModule("LocalDateTimeTimezoneModule");
        module.addSerializer(LocalDateTime.class, new LocalDateTimeWithZoneSerializer());
        module.addDeserializer(LocalDateTime.class, new LocalDateTimeWithZoneDeserializer());
        return module;
    }

    /**
     * 序列化时附加系统时区偏移量
     * LocalDateTime → "2026-03-05T09:13:00+08:00"
     */
    static class LocalDateTimeWithZoneSerializer extends JsonSerializer<LocalDateTime> {
        @Override
        public void serialize(LocalDateTime value, JsonGenerator gen, SerializerProvider serializers)
                throws IOException {
            OffsetDateTime odt = value.atZone(ZoneId.systemDefault()).toOffsetDateTime();
            gen.writeString(odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        }
    }

    /**
     * 反序列化兼容两种格式：
     * - 带时区："2026-03-05T09:13:00+08:00" → 转换到系统时区的 LocalDateTime
     * - 不带时区："2026-03-05T09:13:00" → 直接解析为 LocalDateTime
     */
    static class LocalDateTimeWithZoneDeserializer extends JsonDeserializer<LocalDateTime> {
        @Override
        public LocalDateTime deserialize(JsonParser p, DeserializationContext ctxt)
                throws IOException {
            String text = p.getText();
            try {
                OffsetDateTime odt = OffsetDateTime.parse(text, DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                return odt.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
            } catch (DateTimeParseException e) {
                return LocalDateTime.parse(text, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            }
        }
    }
}
