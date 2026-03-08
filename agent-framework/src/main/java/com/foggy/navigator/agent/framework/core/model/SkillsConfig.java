package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Skills配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SkillsConfig {
    private String directory;
    private List<String> enabled;
}
