package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppUpstreamUserGrantDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamUserGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantUpstreamUserForm;
import com.foggy.navigator.business.agent.repository.ClientAppUpstreamUserGrantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ClientAppUserGrantServiceTest {

    @Mock
    private ClientAppUpstreamUserGrantRepository grantRepository;
    @Mock
    private ClientAppService clientAppService;

    @InjectMocks
    private ClientAppUserGrantService clientAppUserGrantService;

    @Test
    void grantUpstreamUserAccess_success() {
        GrantUpstreamUserForm form = new GrantUpstreamUserForm();
        form.setUpstreamUserId("user_01");
        form.setUpstreamUserToken("tms-token-01");

        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        when(grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId("tenant_1", "app_01", "user_01")).thenReturn(Optional.empty());
        when(grantRepository.save(any(ClientAppUpstreamUserGrantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientAppUpstreamUserGrantDTO dto = clientAppUserGrantService.grantUpstreamUserAccess("tenant_1", "app_01", "admin_1", form);

        assertNotNull(dto);
        assertEquals("user_01", dto.getUpstreamUserId());
        assertEquals("ENABLED", dto.getStatus());

        verify(grantRepository).save(argThat(entity ->
                "tms-token-01".equals(entity.getUpstreamUserToken())));
        assertFalse(Arrays.stream(ClientAppUpstreamUserGrantDTO.class.getDeclaredFields())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet())
                .contains("upstreamUserToken"));
    }

    @Test
    void resolveUpstreamUserToken_success() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setStatus("ENABLED");
        grant.setUpstreamUserToken("tms-token-01");
        when(grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId("tenant_1", "app_01", "user_01")).thenReturn(Optional.of(grant));

        String token = clientAppUserGrantService.resolveUpstreamUserToken("tenant_1", "app_01", "user_01");

        assertEquals("tms-token-01", token);
    }

    @Test
    void resolveUpstreamUserToken_rejects_missing_token() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setStatus("ENABLED");
        when(grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId("tenant_1", "app_01", "user_01")).thenReturn(Optional.of(grant));

        assertThrows(IllegalStateException.class, () ->
                clientAppUserGrantService.resolveUpstreamUserToken("tenant_1", "app_01", "user_01"));
    }

    @Test
    void resolveUpstreamUserToken_rejects_disabled_grant() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setStatus("DISABLED");
        grant.setUpstreamUserToken("tms-token-01");
        when(grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId("tenant_1", "app_01", "user_01")).thenReturn(Optional.of(grant));

        assertThrows(IllegalStateException.class, () ->
                clientAppUserGrantService.resolveUpstreamUserToken("tenant_1", "app_01", "user_01"));
    }

    @Test
    void checkUpstreamUserAccess_throwsIfDisabled() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());

        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setStatus("DISABLED");
        when(grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId("tenant_1", "app_01", "user_01")).thenReturn(Optional.of(grant));

        assertThrows(IllegalStateException.class, () -> {
            clientAppUserGrantService.checkUpstreamUserAccess("tenant_1", "app_01", "user_01");
        });
    }

    @Test
    void updateUpstreamUserGrantStatus_success() {
        ClientAppUpstreamUserGrantEntity grant = new ClientAppUpstreamUserGrantEntity();
        grant.setStatus("ENABLED");
        when(grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId("tenant_1", "app_01", "user_01")).thenReturn(Optional.of(grant));
        when(grantRepository.save(any(ClientAppUpstreamUserGrantEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ClientAppUpstreamUserGrantDTO dto = clientAppUserGrantService.updateUpstreamUserGrantStatus("tenant_1", "app_01", "user_01", "DISABLED");

        assertNotNull(dto);
        assertEquals("DISABLED", dto.getStatus());
    }
    @Test
    void updateUpstreamUserGrantStatus_rejects_missing_app() {
        doThrow(new IllegalArgumentException("App not found"))
                .when(clientAppService).requireClientApp("tenant_1", "app_01");

        assertThrows(IllegalArgumentException.class, () -> {
            clientAppUserGrantService.updateUpstreamUserGrantStatus("tenant_1", "app_01", "user_01", "DISABLED");
        });
    }
}
