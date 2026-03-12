package com.foggy.navigator.agent.framework.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ValidationResult 单元测试 — L1
 */
class ValidationResultTest {

    @Test
    void success_isValid() {
        ValidationResult result = ValidationResult.success();
        assertTrue(result.isValid());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void failure_singleError() {
        ValidationResult result = ValidationResult.failure("ID required");
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
        assertEquals("ID required", result.getErrors().get(0));
    }

    @Test
    void failure_multipleErrors() {
        ValidationResult result = ValidationResult.failure(List.of("error1", "error2"));
        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().size());
    }

    @Test
    void addError_switchesToInvalid() {
        ValidationResult result = ValidationResult.success();
        assertTrue(result.isValid());

        result.addError("something wrong");
        assertFalse(result.isValid());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void addWarning_staysValid() {
        ValidationResult result = ValidationResult.success();
        result.addWarning("consider this");
        assertTrue(result.isValid());
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void addMultipleErrorsAndWarnings() {
        ValidationResult result = ValidationResult.success();
        result.addError("err1");
        result.addError("err2");
        result.addWarning("warn1");

        assertFalse(result.isValid());
        assertEquals(2, result.getErrors().size());
        assertEquals(1, result.getWarnings().size());
    }
}
