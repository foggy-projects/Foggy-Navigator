package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeAuditDTO;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reflection-based security test proving audit DTOs do not expose sensitive fields.
 */
class BusinessFunctionRuntimeAuditDTOSecurityTest {

    private static final Set<String> FORBIDDEN_FIELDS = Set.of(
            "adapterConfigJson",
            "manifestJson",
            "task_scoped_token",
            "taskScopedToken",
            "tokenHash",
            "plainToken"
    );

    @Test
    void auditDTO_does_not_expose_secrets() {
        Set<String> fieldNames = Arrays.stream(BusinessFunctionRuntimeAuditDTO.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        for (String forbidden : FORBIDDEN_FIELDS) {
            assertFalse(fieldNames.contains(forbidden),
                    "Audit DTO must NOT expose field: " + forbidden);
        }
    }

    @Test
    void auditEntity_does_not_expose_secrets() {
        Set<String> fieldNames = Arrays.stream(
                com.foggy.navigator.business.agent.model.entity.BusinessFunctionRuntimeAuditEntity.class.getDeclaredFields())
                .map(Field::getName)
                .collect(Collectors.toSet());

        for (String forbidden : FORBIDDEN_FIELDS) {
            assertFalse(fieldNames.contains(forbidden),
                    "Audit Entity must NOT expose field: " + forbidden);
        }
    }
}
