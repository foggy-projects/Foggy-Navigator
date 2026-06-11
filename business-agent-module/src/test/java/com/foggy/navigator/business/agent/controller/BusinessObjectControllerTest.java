package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessObjectDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppControlPlanePrincipal;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.form.CreateBusinessObjectForm;
import com.foggy.navigator.business.agent.model.form.UpdateBusinessObjectForm;
import com.foggy.navigator.business.agent.service.BusinessObjectService;
import com.foggy.navigator.business.agent.service.ClientAppControlCredentialService;
import com.foggy.navigator.business.agent.service.UpstreamBootstrapRequestService;
import com.foggy.navigator.business.agent.service.UpstreamClientAppAdminCredentialService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

class BusinessObjectControllerTest {

    @Test
    void create_usesControlKeyBusinessObjectScopeAndPrincipalTenant() {
        BusinessObjectService objectService = mock(BusinessObjectService.class);
        ClientAppControlCredentialService controlCredentialService = mock(ClientAppControlCredentialService.class);
        UpstreamClientAppAdminCredentialService adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        BusinessObjectController controller = new BusinessObjectController(
                objectService, controlCredentialService, adminCredentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac-test");
        CreateBusinessObjectForm form = new CreateBusinessObjectForm();
        form.setObjectId("tms.order");
        BusinessObjectDTO dto = new BusinessObjectDTO();
        dto.setObjectId("tms.order");

        when(controlCredentialService.requireAccess(
                same(request),
                eq(ClientAppControlCredentialService.SCOPE_BUSINESS_OBJECT_MANAGE),
                eq(null)))
                .thenReturn(controlPrincipal());
        when(objectService.createBusinessObject("tenant-1", "client-app-control:cacc-1", form)).thenReturn(dto);

        BusinessObjectDTO result = controller.createBusinessObject(request, form);

        assertEquals("tms.order", result.getObjectId());
        verify(objectService).createBusinessObject("tenant-1", "client-app-control:cacc-1", form);
        verifyNoInteractions(adminCredentialService);
    }

    @Test
    void create_prefersUpstreamAdminWhenBothAdminAndControlKeysArePresent() {
        BusinessObjectService objectService = mock(BusinessObjectService.class);
        ClientAppControlCredentialService controlCredentialService = mock(ClientAppControlCredentialService.class);
        UpstreamClientAppAdminCredentialService adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        BusinessObjectController controller = new BusinessObjectController(
                objectService, controlCredentialService, adminCredentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(ClientAppControlCredentialService.HEADER_CONTROL_KEY, "cac-test");
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "naa-new");
        request.addHeader("X-Tenant-Id", "tenant-2");
        CreateBusinessObjectForm form = new CreateBusinessObjectForm();
        form.setObjectId("tms.order");
        BusinessObjectDTO dto = new BusinessObjectDTO();
        dto.setObjectId("tms.order");

        UpstreamClientAppAdminPrincipal principal = adminPrincipal(Set.of("tenant-2"));
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_BUSINESS_OBJECT_MANAGE)))
                .thenReturn(principal);
        when(objectService.createBusinessObject("tenant-2", "upstream-admin:ucaac-1", form)).thenReturn(dto);

        BusinessObjectDTO result = controller.createBusinessObject(request, form);

        assertEquals("tms.order", result.getObjectId());
        verify(objectService).createBusinessObject("tenant-2", "upstream-admin:ucaac-1", form);
        verifyNoInteractions(controlCredentialService);
    }

    @Test
    void update_usesUpstreamAdminScopeAndHeaderTenant() {
        BusinessObjectService objectService = mock(BusinessObjectService.class);
        ClientAppControlCredentialService controlCredentialService = mock(ClientAppControlCredentialService.class);
        UpstreamClientAppAdminCredentialService adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        BusinessObjectController controller = new BusinessObjectController(
                objectService, controlCredentialService, adminCredentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "naa-new");
        request.addHeader("X-Tenant-Id", "tenant-2");
        UpdateBusinessObjectForm form = new UpdateBusinessObjectForm();
        form.setName("Order");
        BusinessObjectDTO dto = new BusinessObjectDTO();
        dto.setObjectId("tms.order");

        UpstreamClientAppAdminPrincipal principal = adminPrincipal(Set.of("tenant-1", "tenant-2"));
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_BUSINESS_OBJECT_MANAGE)))
                .thenReturn(principal);
        when(objectService.updateBusinessObject("tenant-2", "tms.order", "upstream-admin:ucaac-1", form))
                .thenReturn(dto);

        BusinessObjectDTO result = controller.updateBusinessObject(request, "tms.order", form);

        assertEquals("tms.order", result.getObjectId());
        verify(objectService).updateBusinessObject("tenant-2", "tms.order", "upstream-admin:ucaac-1", form);
        verifyNoInteractions(controlCredentialService);
    }

    @Test
    void update_acceptsAggregateUpstreamSystemTenantAuthorizationForDerivedNavigatorTenant() {
        BusinessObjectService objectService = mock(BusinessObjectService.class);
        ClientAppControlCredentialService controlCredentialService = mock(ClientAppControlCredentialService.class);
        UpstreamClientAppAdminCredentialService adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        BusinessObjectController controller = new BusinessObjectController(
                objectService, controlCredentialService, adminCredentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "naa-new");
        request.addHeader("X-Tenant-Id", "nav_tms_88800");
        UpdateBusinessObjectForm form = new UpdateBusinessObjectForm();
        form.setName("Order");
        BusinessObjectDTO dto = new BusinessObjectDTO();
        dto.setObjectId("tms.order");

        UpstreamClientAppAdminPrincipal principal = adminPrincipal(Set.of("TMS"));
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_BUSINESS_OBJECT_MANAGE)))
                .thenReturn(principal);
        when(objectService.updateBusinessObject("nav_tms_88800", "tms.order", "upstream-admin:ucaac-1", form))
                .thenReturn(dto);

        BusinessObjectDTO result = controller.updateBusinessObject(request, "tms.order", form);

        assertEquals("tms.order", result.getObjectId());
        verify(adminCredentialService, never()).requireTenant(any(), anyString());
        verify(objectService).updateBusinessObject("nav_tms_88800", "tms.order", "upstream-admin:ucaac-1", form);
        verifyNoInteractions(controlCredentialService);
    }

    @Test
    void get_usesSingleTenantFromUpstreamAdminCredentialWhenHeaderTenantMissing() {
        BusinessObjectService objectService = mock(BusinessObjectService.class);
        ClientAppControlCredentialService controlCredentialService = mock(ClientAppControlCredentialService.class);
        UpstreamClientAppAdminCredentialService adminCredentialService = mock(UpstreamClientAppAdminCredentialService.class);
        BusinessObjectController controller = new BusinessObjectController(
                objectService, controlCredentialService, adminCredentialService);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(UpstreamClientAppAdminCredentialService.HEADER_ADMIN_KEY, "naa-new");
        BusinessObjectDTO dto = new BusinessObjectDTO();
        dto.setObjectId("tms.order");

        UpstreamClientAppAdminPrincipal principal = adminPrincipal(Set.of("tenant-1"));
        when(adminCredentialService.requireAccess(
                same(request),
                eq(UpstreamBootstrapRequestService.SCOPE_BUSINESS_OBJECT_MANAGE)))
                .thenReturn(principal);
        when(objectService.getBusinessObject("tenant-1", "tms.order")).thenReturn(dto);

        BusinessObjectDTO result = controller.getBusinessObject(request, "tms.order");

        assertEquals("tms.order", result.getObjectId());
        verify(adminCredentialService, never()).requireTenant(any(), anyString());
        verify(objectService).getBusinessObject("tenant-1", "tms.order");
    }

    private ClientAppControlPlanePrincipal controlPrincipal() {
        return ClientAppControlPlanePrincipal.builder()
                .tenantId("tenant-1")
                .clientAppId("capp-1")
                .credentialId("cacc-1")
                .actorUserId("client-app-control:cacc-1")
                .principalType("CLIENT_APP")
                .principalId("capp-1")
                .scopes(Set.of(ClientAppControlCredentialService.SCOPE_BUSINESS_OBJECT_MANAGE))
                .build();
    }

    private UpstreamClientAppAdminPrincipal adminPrincipal(Set<String> tenantIds) {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("ucaac-1")
                .principalId("TMS")
                .upstreamSystemId("TMS")
                .authorizedClientAppNamespace("TMS")
                .authorizedTenantIds(tenantIds)
                .scopes(Set.of(UpstreamBootstrapRequestService.SCOPE_BUSINESS_OBJECT_MANAGE))
                .build();
    }
}
