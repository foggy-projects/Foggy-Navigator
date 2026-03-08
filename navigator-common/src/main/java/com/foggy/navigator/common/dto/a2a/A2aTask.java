package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A2A Task (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aTask {
    private String id;
    private String contextId;
    private A2aTaskStatus status;
    private List<A2aMessage> history;
    private List<A2aArtifact> artifacts;
    private Map<String, Object> metadata;
}
