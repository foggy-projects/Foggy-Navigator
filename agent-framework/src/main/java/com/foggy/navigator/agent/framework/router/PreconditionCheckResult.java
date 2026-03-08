package com.foggy.navigator.agent.framework.router;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 前置条件检查结果
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PreconditionCheckResult {
    private boolean satisfied;
    private List<String> missedConditions;
    private String suggestionMessage;

    public static PreconditionCheckResult satisfied() {
        return PreconditionCheckResult.builder().satisfied(true).build();
    }

    public static PreconditionCheckResult notSatisfied(List<String> missed, String suggestion) {
        return PreconditionCheckResult.builder()
                .satisfied(false)
                .missedConditions(missed)
                .suggestionMessage(suggestion)
                .build();
    }
}
