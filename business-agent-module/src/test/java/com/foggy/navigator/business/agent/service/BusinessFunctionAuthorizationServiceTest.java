package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessFunctionAuthorizationServiceTest {

    @Mock
    private ClientAppService clientAppService;
    @Mock
    private ClientAppUserGrantService userGrantService;
    @Mock
    private SkillRegistryService skillRegistryService;
    @Mock
    private BusinessFunctionRegistryService functionRegistryService;

    @InjectMocks
    private BusinessFunctionAuthorizationService authorizationService;

    @Test
    void resolveExecutableBusinessFunction_success() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_1")).thenReturn(new ClientAppEntity());
        doNothing().when(userGrantService).checkUpstreamUserAccess("tenant_1", "app_1", "user_1");
        doNothing().when(skillRegistryService).checkClientAppSkillAccess("tenant_1", "app_1", "skill_1");
        doNothing().when(skillRegistryService).checkSkillFunctionAccess("tenant_1", "skill_1", "func_1");

        BusinessFunctionRuntimeContextDTO mockContext = new BusinessFunctionRuntimeContextDTO();
        when(functionRegistryService.resolveClientAppFunction("tenant_1", "app_1", "func_1", "v1")).thenReturn(mockContext);

        BusinessFunctionRuntimeContextDTO result = authorizationService.resolveExecutableBusinessFunction(
                "tenant_1", "app_1", "user_1", "skill_1", "func_1", "v1"
        );

        assertNotNull(result);
    }

    @Test
    void disabled_or_missing_upstream_user_grant_rejected() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_1")).thenReturn(new ClientAppEntity());
        doThrow(new IllegalStateException("User grant missing or disabled"))
                .when(userGrantService).checkUpstreamUserAccess("tenant_1", "app_1", "user_1");

        assertThrows(IllegalStateException.class, () -> {
            authorizationService.resolveExecutableBusinessFunction(
                    "tenant_1", "app_1", "user_1", "skill_1", "func_1", "v1"
            );
        });
        verify(skillRegistryService, never()).checkClientAppSkillAccess(anyString(), anyString(), anyString());
    }

    @Test
    void missing_or_disabled_client_app_skill_grant_rejected() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_1")).thenReturn(new ClientAppEntity());
        doNothing().when(userGrantService).checkUpstreamUserAccess("tenant_1", "app_1", "user_1");
        doThrow(new IllegalStateException("Skill grant missing or disabled"))
                .when(skillRegistryService).checkClientAppSkillAccess("tenant_1", "app_1", "skill_1");

        assertThrows(IllegalStateException.class, () -> {
            authorizationService.resolveExecutableBusinessFunction(
                    "tenant_1", "app_1", "user_1", "skill_1", "func_1", "v1"
            );
        });
        verify(skillRegistryService, never()).checkSkillFunctionAccess(anyString(), anyString(), anyString());
    }

    @Test
    void missing_or_disabled_skill_function_allowlist_rejected() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_1")).thenReturn(new ClientAppEntity());
        doNothing().when(userGrantService).checkUpstreamUserAccess("tenant_1", "app_1", "user_1");
        doNothing().when(skillRegistryService).checkClientAppSkillAccess("tenant_1", "app_1", "skill_1");
        doThrow(new IllegalStateException("Function allowlist missing or disabled"))
                .when(skillRegistryService).checkSkillFunctionAccess("tenant_1", "skill_1", "func_1");

        assertThrows(IllegalStateException.class, () -> {
            authorizationService.resolveExecutableBusinessFunction(
                    "tenant_1", "app_1", "user_1", "skill_1", "func_1", "v1"
            );
        });
        verify(functionRegistryService, never()).resolveClientAppFunction(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void disabled_function_version_client_app_function_grant_rejected() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_1")).thenReturn(new ClientAppEntity());
        doNothing().when(userGrantService).checkUpstreamUserAccess("tenant_1", "app_1", "user_1");
        doNothing().when(skillRegistryService).checkClientAppSkillAccess("tenant_1", "app_1", "skill_1");
        doNothing().when(skillRegistryService).checkSkillFunctionAccess("tenant_1", "skill_1", "func_1");

        doThrow(new IllegalStateException("Function grant missing or disabled"))
                .when(functionRegistryService).resolveClientAppFunction("tenant_1", "app_1", "func_1", "v1");

        assertThrows(IllegalStateException.class, () -> {
            authorizationService.resolveExecutableBusinessFunction(
                    "tenant_1", "app_1", "user_1", "skill_1", "func_1", "v1"
            );
        });
    }
}
