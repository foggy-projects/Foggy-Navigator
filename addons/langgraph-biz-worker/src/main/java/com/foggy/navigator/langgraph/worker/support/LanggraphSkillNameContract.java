package com.foggy.navigator.langgraph.worker.support;

import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

public final class LanggraphSkillNameContract {

    public static final String CANONICAL_KEY = "skill_name";
    public static final String JAVA_ALIAS_KEY = "skillName";

    private static final String LEGACY_SNAKE_KEY = "skill_id";
    private static final String LEGACY_CAMEL_KEY = "skillId";
    private static final String[] ORDERED_KEYS = {
            CANONICAL_KEY,
            JAVA_ALIAS_KEY,
            LEGACY_SNAKE_KEY,
            LEGACY_CAMEL_KEY
    };

    private LanggraphSkillNameContract() {
    }

    public static String resolve(Map<String, Object> values, BiConsumer<String, String> deprecatedAliasReporter) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        Map<String, String> present = new LinkedHashMap<>();
        for (String key : ORDERED_KEYS) {
            Object value = values.get(key);
            if (value instanceof String text && StringUtils.hasText(text)) {
                present.put(key, text.trim());
            }
        }
        if (present.isEmpty()) {
            return null;
        }

        String resolved = present.values().iterator().next();
        for (Map.Entry<String, String> entry : present.entrySet()) {
            if (!resolved.equals(entry.getValue())) {
                throw new IllegalArgumentException("skill_name aliases must resolve to the same value");
            }
        }

        reportDeprecatedAlias(present, deprecatedAliasReporter, LEGACY_SNAKE_KEY);
        reportDeprecatedAlias(present, deprecatedAliasReporter, LEGACY_CAMEL_KEY);
        return resolved;
    }

    private static void reportDeprecatedAlias(Map<String, String> present,
                                              BiConsumer<String, String> deprecatedAliasReporter,
                                              String key) {
        if (deprecatedAliasReporter != null && present.containsKey(key)) {
            deprecatedAliasReporter.accept(key, present.get(key));
        }
    }
}
