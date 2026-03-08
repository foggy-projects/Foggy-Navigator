package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A2A Message (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aMessage {
    private String role;
    private List<A2aPart> parts;
    private String taskId;
    private String contextId;
    private Map<String, Object> metadata;

    public static A2aMessage user(List<A2aPart> parts) {
        return A2aMessage.builder().role("user").parts(parts).build();
    }

    public static A2aMessage agent(List<A2aPart> parts) {
        return A2aMessage.builder().role("agent").parts(parts).build();
    }
}
