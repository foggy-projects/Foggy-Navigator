package com.foggy.navigator.agent.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 分派规则
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegationRule {
    private String name;
    private TriggerConfig trigger;
    private String target;
    private Map<String, Object> preconditions;
    private List<ContextMapping> contextMapping;
}
