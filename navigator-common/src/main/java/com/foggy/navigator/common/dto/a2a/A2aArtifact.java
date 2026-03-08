package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * A2A Artifact (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aArtifact {
    private String artifactId;
    private String name;
    private String description;
    private List<A2aPart> parts;
    private Map<String, Object> metadata;
}
