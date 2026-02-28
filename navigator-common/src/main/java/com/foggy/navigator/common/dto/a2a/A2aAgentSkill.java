package com.foggy.navigator.common.dto.a2a;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A2A Agent Skill (Google A2A Protocol)
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class A2aAgentSkill {
    private String id;
    private String name;
    private String description;
    private List<String> tags;
    private List<String> examples;
}
