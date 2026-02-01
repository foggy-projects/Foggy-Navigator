package com.foggy.navigator.agent.framework.core.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 分派配置
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DelegationConfig {
    private List<DelegationRule> rules;
}
