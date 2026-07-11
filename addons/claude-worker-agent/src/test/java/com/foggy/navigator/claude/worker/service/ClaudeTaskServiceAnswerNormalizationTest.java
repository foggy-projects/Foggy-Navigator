package com.foggy.navigator.claude.worker.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClaudeTaskServiceAnswerNormalizationTest {

    @Test
    void normalizeTaskResponseAnswers_preservesStringsAndJoinsMultiSelectValues() {
        Map<String, String> answers = ClaudeTaskService.normalizeTaskResponseAnswers(Map.of(
                "single", "Option A",
                "multiple", List.of("Option B", "Option C")));

        assertEquals("Option A", answers.get("single"));
        assertEquals("Option B, Option C", answers.get("multiple"));
    }

    @Test
    void normalizeTaskResponseAnswers_rejectsNonStringValues() {
        assertThrows(IllegalArgumentException.class,
                () -> ClaudeTaskService.normalizeTaskResponseAnswers(Map.of("question", List.of(1, 2))));
    }
}
