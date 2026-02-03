package com.foggy.navigator.common.dto;

import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Skill 配置 DTO
 */
@Data
public class SkillConfigDTO {

    private String id;
    private String tenantId;
    private SkillScope scope;
    private String agentId;
    private String name;
    private String description;
    private List<String> triggerKeywords;
    private List<String> intents;
    private String executionLogic;
    private String outputFormat;
    private String delegationCondition;
    private String markdownContent;
    private SkillStatus status;
    private Integer priority;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
