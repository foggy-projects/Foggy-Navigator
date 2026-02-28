package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A2A Agent Card (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aAgentCard {
    private String name;
    private String description;
    private String url;
    private String version;
    private List<A2aAgentSkill> skills;
    private A2aAgentCapabilities capabilities;
}
