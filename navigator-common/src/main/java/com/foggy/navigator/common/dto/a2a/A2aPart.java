package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

/**
 * A2A Message Part (Google A2A Protocol)
 * type: text / data / file
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aPart {
    private String type;
    private String text;
    private Map<String, Object> data;
    private String mimeType;
    private String uri;

    public static A2aPart text(String text) {
        return A2aPart.builder().type("text").text(text).build();
    }

    public static A2aPart data(Map<String, Object> data) {
        return A2aPart.builder().type("data").data(data).build();
    }
}
