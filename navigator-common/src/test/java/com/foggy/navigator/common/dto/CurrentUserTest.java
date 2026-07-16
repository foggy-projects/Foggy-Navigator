package com.foggy.navigator.common.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CurrentUserTest {

    @Test
    void matchesOnlyExactDelimitedRoles() {
        CurrentUser user = CurrentUser.builder()
                .roles("VIEWER, TENANT_ADMIN, SUPER_ADMIN")
                .build();

        assertEquals(List.of("VIEWER", "TENANT_ADMIN", "SUPER_ADMIN"), user.getRoleList());
        assertTrue(user.hasRole("TENANT_ADMIN"));
        assertTrue(user.isTenantAdmin());
        assertTrue(user.isSuperAdmin());
        assertFalse(user.hasRole("TENANT"));
    }

    @Test
    void doesNotTreatRoleSubstringsAsPrivileges() {
        CurrentUser user = CurrentUser.builder()
                .roles("NOT_TENANT_ADMIN,NOT_SUPER_ADMIN")
                .build();

        assertFalse(user.hasRole("TENANT_ADMIN"));
        assertFalse(user.isTenantAdmin());
        assertFalse(user.isSuperAdmin());
    }
}
