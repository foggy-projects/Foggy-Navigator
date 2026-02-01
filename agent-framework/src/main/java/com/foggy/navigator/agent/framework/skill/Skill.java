package com.foggy.navigator.agent.framework.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill定义
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Skill {
    private String id;
    private String name;
    private String agentId;
    private List<String> triggerKeywords;
    private List<String> intents;
    private String description;
    private String executionLogic;
    private String outputFormat;
    private String delegationCondition;
    private String markdownPath;
    private LocalDateTime loadedAt;
}
