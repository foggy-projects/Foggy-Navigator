package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamRouteEntity;
import com.foggy.navigator.business.agent.model.form.UpsertClientAppUpstreamRouteForm;
import com.foggy.navigator.business.agent.repository.ClientAppUpstreamRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientAppUpstreamRouteServiceTest {

    private ClientAppUpstreamRouteRepository routeRepository;
    private ClientAppService clientAppService;
    private ClientAppUpstreamRouteService service;

    @BeforeEach
    void setUp() {
        routeRepository = mock(ClientAppUpstreamRouteRepository.class);
        clientAppService = mock(ClientAppService.class);
        service = new ClientAppUpstreamRouteService(routeRepository, clientAppService);

        when(clientAppService.requireClientApp("tenant-1", "app-1")).thenReturn(clientApp());
        when(routeRepository.save(any())).thenAnswer(inv -> {
            ClientAppUpstreamRouteEntity entity = inv.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return entity;
        });
    }

    @Test
    void upsertRoute_creates_enabled_route() {
        UpsertClientAppUpstreamRouteForm form = form("http://localhost:3000", "X-World-Sim-Token", null);

        var dto = service.upsertRoute("tenant-1", "app-1", "world-sim", "actor-1", form);

        assertEquals("world-sim", dto.getUpstreamRef());
        assertEquals("http://localhost:3000", dto.getBaseUrl());
        assertEquals("X-World-Sim-Token", dto.getUserTokenHeader());
        assertEquals(ClientAppUpstreamRouteService.STATUS_ENABLED, dto.getStatus());
        verify(routeRepository).save(argThat(entity ->
                "tenant-1".equals(entity.getTenantId())
                        && "app-1".equals(entity.getClientAppId())
                        && "actor-1".equals(entity.getCreatedBy())));
    }

    @Test
    void upsertRoute_updates_existing_route_and_can_disable() {
        ClientAppUpstreamRouteEntity existing = route("world-sim", "http://old", "ENABLED");
        existing.setId(9L);
        when(routeRepository.findByTenantIdAndClientAppIdAndUpstreamRef("tenant-1", "app-1", "world-sim"))
                .thenReturn(Optional.of(existing));

        var dto = service.upsertRoute("tenant-1", "app-1", "world-sim", "actor-2",
                form("https://new.example", null, "DISABLED"));

        assertEquals(9L, dto.getId());
        assertEquals("https://new.example", existing.getBaseUrl());
        assertNull(existing.getUserTokenHeader());
        assertEquals(ClientAppUpstreamRouteService.STATUS_DISABLED, existing.getStatus());
        assertNull(existing.getCreatedBy());
    }

    @Test
    void upsertRoute_rejects_invalid_ref_url_and_header() {
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertRoute("tenant-1", "app-1", "../bad", "actor", form("http://ok", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertRoute("tenant-1", "app-1", "world-sim", "actor", form("file:///tmp", null, null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertRoute("tenant-1", "app-1", "world-sim", "actor", form("http://ok", "Authorization", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertRoute("tenant-1", "app-1", "world-sim", "actor", form("http://ok", " Authorization ", null)));
        assertThrows(IllegalArgumentException.class,
                () -> service.upsertRoute("tenant-1", "app-1", "world-sim", "actor", form("http://ok", " X-Navigator-Task-Id ", null)));
    }

    @Test
    void resolveEnabledRoute_is_scoped_to_tenant_client_app_and_status() {
        when(routeRepository.findByTenantIdAndClientAppIdAndUpstreamRefAndStatus(
                "tenant-1", "app-1", "world-sim", ClientAppUpstreamRouteService.STATUS_ENABLED))
                .thenReturn(Optional.of(route("world-sim", "http://world-sim", "ENABLED")));

        Optional<ClientAppUpstreamRouteService.ResolvedUpstreamRoute> route =
                service.resolveEnabledRoute("tenant-1", "app-1", "world-sim");

        assertTrue(route.isPresent());
        assertEquals("http://world-sim", route.get().getBaseUrl());
        verify(routeRepository).findByTenantIdAndClientAppIdAndUpstreamRefAndStatus(
                "tenant-1", "app-1", "world-sim", ClientAppUpstreamRouteService.STATUS_ENABLED);
    }

    @Test
    void resolveEnabledRoute_returns_empty_when_no_enabled_scoped_route_exists() {
        when(routeRepository.findByTenantIdAndClientAppIdAndUpstreamRefAndStatus(
                "tenant-1", "app-1", "world-sim", ClientAppUpstreamRouteService.STATUS_ENABLED))
                .thenReturn(Optional.empty());

        Optional<ClientAppUpstreamRouteService.ResolvedUpstreamRoute> route =
                service.resolveEnabledRoute("tenant-1", "app-1", "world-sim");

        assertTrue(route.isEmpty());
        verify(routeRepository).findByTenantIdAndClientAppIdAndUpstreamRefAndStatus(
                "tenant-1", "app-1", "world-sim", ClientAppUpstreamRouteService.STATUS_ENABLED);
    }

    @Test
    void listRoutes_requires_client_app_scope() {
        when(routeRepository.findByTenantIdAndClientAppIdOrderByUpstreamRefAsc("tenant-1", "app-1"))
                .thenReturn(List.of(route("world-sim", "http://world-sim", "ENABLED")));

        var routes = service.listRoutes("tenant-1", "app-1");

        assertEquals(1, routes.size());
        verify(clientAppService).requireClientApp("tenant-1", "app-1");
    }

    private UpsertClientAppUpstreamRouteForm form(String baseUrl, String userTokenHeader, String status) {
        UpsertClientAppUpstreamRouteForm form = new UpsertClientAppUpstreamRouteForm();
        form.setBaseUrl(baseUrl);
        form.setUserTokenHeader(userTokenHeader);
        form.setStatus(status);
        return form;
    }

    private ClientAppUpstreamRouteEntity route(String upstreamRef, String baseUrl, String status) {
        ClientAppUpstreamRouteEntity entity = new ClientAppUpstreamRouteEntity();
        entity.setTenantId("tenant-1");
        entity.setClientAppId("app-1");
        entity.setUpstreamRef(upstreamRef);
        entity.setBaseUrl(baseUrl);
        entity.setStatus(status);
        return entity;
    }

    private ClientAppEntity clientApp() {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setTenantId("tenant-1");
        entity.setClientAppId("app-1");
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        return entity;
    }
}
