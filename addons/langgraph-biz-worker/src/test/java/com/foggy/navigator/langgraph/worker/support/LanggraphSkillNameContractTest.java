package com.foggy.navigator.langgraph.worker.support;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LanggraphSkillNameContractTest {

    @Test
    void resolve_prefersCanonicalAndAcceptsMatchingJavaAlias() {
        String resolved = LanggraphSkillNameContract.resolve(Map.of(
                "skill_name", "order-assistant",
                "skillName", "order-assistant"), null);

        assertEquals("order-assistant", resolved);
    }

    @Test
    void resolve_acceptsLegacyAliasAndReportsDeprecation() {
        List<String> deprecated = new ArrayList<>();

        String resolved = LanggraphSkillNameContract.resolve(Map.of("skill_id", "legacy-skill"),
                (key, ignored) -> deprecated.add(key));

        assertEquals("legacy-skill", resolved);
        assertEquals(List.of("skill_id"), deprecated);
    }

    @Test
    void resolve_rejectsConflictingAliases() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                LanggraphSkillNameContract.resolve(Map.of(
                        "skill_name", "canonical-skill",
                        "skillId", "legacy-skill"), null));

        assertEquals("skill_name aliases must resolve to the same value", error.getMessage());
    }
}
