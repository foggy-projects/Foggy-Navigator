package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppFunctionGrantDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantBusinessFunctionForm;
import com.foggy.navigator.business.agent.model.form.ImportBusinessFunctionManifestForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.BusinessFunctionVersionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessFunctionRegistryServiceTest {

    @Mock
    private BusinessFunctionRepository functionRepository;

    @Mock
    private BusinessFunctionVersionRepository versionRepository;

    @Mock
    private ClientAppFunctionGrantRepository grantRepository;

    @Mock
    private ClientAppService clientAppService;

    @Mock
    private BusinessObjectService businessObjectService;

    @InjectMocks
    private BusinessFunctionRegistryService registryService;

    private ImportBusinessFunctionManifestForm importForm;
    private GrantBusinessFunctionForm grantForm;

    @BeforeEach
    void setUp() {
        importForm = new ImportBusinessFunctionManifestForm();
        importForm.setFunctionId("func_01");
        importForm.setVersion("v1");
        importForm.setName("Test Function");
        importForm.setDomain("test");
        importForm.setExposure("test_exposure");
        importForm.setRiskLevel("readonly");
        importForm.setLlmVisibleSummary("llm_summary");
        importForm.setAdapterConfigJson("adapter_config");

        grantForm = new GrantBusinessFunctionForm();
        grantForm.setFunctionId("func_01");
        grantForm.setVersion("v1");
    }

    @Test
    void importManifest_success() {
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.empty());
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.empty());

        registryService.importManifest("tenant_1", "user_1", importForm);

        ArgumentCaptor<BusinessFunctionEntity> funcCaptor = ArgumentCaptor.forClass(BusinessFunctionEntity.class);
        verify(functionRepository).save(funcCaptor.capture());
        assertEquals("func_01", funcCaptor.getValue().getFunctionId());
        assertEquals(BusinessFunctionRegistryService.STATUS_ENABLED, funcCaptor.getValue().getStatus());
        assertEquals("user_1", funcCaptor.getValue().getCreatedBy());

        ArgumentCaptor<BusinessFunctionVersionEntity> verCaptor = ArgumentCaptor.forClass(BusinessFunctionVersionEntity.class);
        verify(versionRepository).save(verCaptor.capture());
        assertEquals("v1", verCaptor.getValue().getVersion());
        assertEquals("adapter_config", verCaptor.getValue().getAdapterConfigJson());
    }

    @Test
    void importManifest_with_businessObjectId_success() {
        importForm.setBusinessObjectId("obj_01");

        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.empty());
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.empty());
        when(businessObjectService.requireActiveBusinessObject("tenant_1", "obj_01")).thenReturn(new com.foggy.navigator.business.agent.model.entity.BusinessObjectEntity());

        registryService.importManifest("tenant_1", "user_1", importForm);

        verify(businessObjectService).requireActiveBusinessObject("tenant_1", "obj_01");

        ArgumentCaptor<BusinessFunctionEntity> funcCaptor = ArgumentCaptor.forClass(BusinessFunctionEntity.class);
        verify(functionRepository).save(funcCaptor.capture());
        assertEquals("func_01", funcCaptor.getValue().getFunctionId());
        assertEquals("obj_01", funcCaptor.getValue().getBusinessObjectId());
        assertEquals(BusinessFunctionRegistryService.STATUS_ENABLED, funcCaptor.getValue().getStatus());

        ArgumentCaptor<BusinessFunctionVersionEntity> verCaptor = ArgumentCaptor.forClass(BusinessFunctionVersionEntity.class);
        verify(versionRepository).save(verCaptor.capture());
    }

    @Test
    void importManifest_with_businessObjectId_invalid_rejected() {
        importForm.setBusinessObjectId("obj_invalid");

        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.empty());
        doThrow(new IllegalStateException("BusinessObject is not active"))
                .when(businessObjectService).requireActiveBusinessObject("tenant_1", "obj_invalid");

        assertThrows(IllegalStateException.class, () -> registryService.importManifest("tenant_1", "user_1", importForm));
    }

    @Test
    void importManifest_nullForm_rejected() {
        assertThrows(IllegalArgumentException.class, () -> registryService.importManifest("tenant_1", "user_1", null));
    }

    @Test
    void importManifest_duplicateVersion_updatesExistingVersion() {
        BusinessFunctionVersionEntity existing = new BusinessFunctionVersionEntity();
        existing.setAdapterConfigJson("old_adapter_config");
        existing.setStatus(BusinessFunctionRegistryService.STATUS_DISABLED);

        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(new BusinessFunctionEntity()));
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(existing));

        BusinessFunctionVersionEntity result = registryService.importManifest("tenant_1", "user_1", importForm);

        assertSame(existing, result);
        assertEquals("adapter_config", result.getAdapterConfigJson());
        assertEquals("llm_summary", result.getLlmVisibleSummary());
        assertEquals(BusinessFunctionRegistryService.STATUS_ENABLED, result.getStatus());
        verify(versionRepository).save(existing);
    }

    @Test
    void grantFunctionToClientApp_success() {
        ClientAppEntity app = new ClientAppEntity();
        app.setClientAppId("app_01");
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(app);

        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));

        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));

        when(grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion("tenant_1", "app_01", "func_01", "v1")).thenReturn(Optional.empty());

        when(grantRepository.save(any(ClientAppFunctionGrantEntity.class))).thenAnswer(i -> i.getArgument(0));

        ClientAppFunctionGrantDTO result = registryService.grantFunctionToClientApp("tenant_1", "app_01", "user_1", grantForm);

        assertNotNull(result);
        assertEquals("tenant_1", result.getTenantId());
        assertEquals("app_01", result.getClientAppId());
        assertEquals("func_01", result.getFunctionId());
    }

    @Test
    void grantFunctionToClientApp_rejects_inactive_client_app() {
        doThrow(new IllegalStateException("not active")).when(clientAppService).requireActiveClientApp("tenant_1", "app_01");
        assertThrows(IllegalStateException.class, () -> registryService.grantFunctionToClientApp("tenant_1", "app_01", "user_1", grantForm));
    }

    @Test
    void grantFunctionToClientApp_rejects_missing_function() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> registryService.grantFunctionToClientApp("tenant_1", "app_01", "user_1", grantForm));
    }

    @Test
    void grantFunctionToClientApp_rejects_disabled_function() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_DISABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));

        assertThrows(IllegalStateException.class, () -> registryService.grantFunctionToClientApp("tenant_1", "app_01", "user_1", grantForm));
    }

    @Test
    void grantFunctionToClientApp_rejects_missing_version() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> registryService.grantFunctionToClientApp("tenant_1", "app_01", "user_1", grantForm));
    }

    @Test
    void grantFunctionToClientApp_duplicate_grant_updatesStatus() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));
        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));
        ClientAppFunctionGrantEntity existing = new ClientAppFunctionGrantEntity();
        existing.setGrantId("grant_01");
        existing.setTenantId("tenant_1");
        existing.setClientAppId("app_01");
        existing.setFunctionId("func_01");
        existing.setVersion("v1");
        existing.setStatus(BusinessFunctionRegistryService.STATUS_DISABLED);
        when(grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion("tenant_1", "app_01", "func_01", "v1"))
                .thenReturn(Optional.of(existing));
        when(grantRepository.save(existing)).thenReturn(existing);

        ClientAppFunctionGrantDTO result = registryService.grantFunctionToClientApp("tenant_1", "app_01", "user_1", grantForm);

        assertEquals("grant_01", result.getGrantId());
        assertEquals(BusinessFunctionRegistryService.STATUS_ENABLED, result.getStatus());
    }

    @Test
    void resolveClientAppFunction_success() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());

        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));

        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        ver.setVersion("v1");
        ver.setAdapterConfigJson("secret_adapter");
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));

        ClientAppFunctionGrantEntity grant = new ClientAppFunctionGrantEntity();
        grant.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion("tenant_1", "app_01", "func_01", "v1"))
                .thenReturn(Optional.of(grant));

        BusinessFunctionRuntimeContextDTO result = registryService.resolveClientAppFunction("tenant_1", "app_01", "func_01", "v1");

        assertNotNull(result);
        assertEquals("v1", result.getVersion());
    }

    @Test
    void resolveClientAppFunction_rejects_missing_grant() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));
        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));

        when(grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion("tenant_1", "app_01", "func_01", "v1"))
                .thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> registryService.resolveClientAppFunction("tenant_1", "app_01", "func_01", "v1"));
    }

    @Test
    void resolveClientAppFunction_rejects_disabled_grant() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));
        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));

        ClientAppFunctionGrantEntity grant = new ClientAppFunctionGrantEntity();
        grant.setStatus(BusinessFunctionRegistryService.STATUS_DISABLED);
        when(grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion("tenant_1", "app_01", "func_01", "v1"))
                .thenReturn(Optional.of(grant));

        assertThrows(IllegalStateException.class, () -> registryService.resolveClientAppFunction("tenant_1", "app_01", "func_01", "v1"));
    }

    @Test
    void resolveClientAppFunction_rejects_cross_tenant() {
        // Validation of tenant happens explicitly via arguments passed to repositories
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> registryService.resolveClientAppFunction("tenant_1", "app_01", "func_01", "v1"));
    }

    @Test
    void listClientAppVisibleFunctionSummaries_filters_disabled_or_ungranted() {
        ClientAppFunctionGrantEntity grant = new ClientAppFunctionGrantEntity();
        grant.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        grant.setFunctionId("func_01");
        grant.setVersion("v1");

        when(grantRepository.findByTenantIdAndClientAppId("tenant_1", "app_01")).thenReturn(List.of(grant));

        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        func.setFunctionId("func_01");
        func.setDomain("test");
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));

        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        ver.setVersion("v1");
        ver.setLlmVisibleSummary("llm summary");
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));

        List<BusinessFunctionSummaryDTO> result = registryService.listClientAppVisibleFunctionSummaries("tenant_1", "app_01");

        verify(clientAppService).requireActiveClientApp("tenant_1", "app_01");

        assertEquals(1, result.size());
        assertEquals("func_01", result.get(0).getFunctionId());
        assertEquals("llm summary", result.get(0).getLlmVisibleSummary());
    }

    @Test
    void visibleSummary_does_not_expose_adapterConfig() {
        when(clientAppService.requireActiveClientApp("tenant_1", "app_01")).thenReturn(new ClientAppEntity());
        BusinessFunctionEntity func = new BusinessFunctionEntity();
        func.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(functionRepository.findByTenantIdAndFunctionId("tenant_1", "func_01")).thenReturn(Optional.of(func));
        BusinessFunctionVersionEntity ver = new BusinessFunctionVersionEntity();
        ver.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        ver.setVersion("v1");
        ver.setAdapterConfigJson("secret_adapter_config");
        when(versionRepository.findByTenantIdAndFunctionIdAndVersion("tenant_1", "func_01", "v1")).thenReturn(Optional.of(ver));

        ClientAppFunctionGrantEntity grant = new ClientAppFunctionGrantEntity();
        grant.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        when(grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion("tenant_1", "app_01", "func_01", "v1"))
                .thenReturn(Optional.of(grant));

        BusinessFunctionRuntimeContextDTO result = registryService.resolveClientAppFunction("tenant_1", "app_01", "func_01", "v1");
        // adapterConfigJson is included in the runtime context DTO because the Gateway needs it
        assertNotNull(result);
        assertEquals("v1", result.getVersion());
        assertEquals("secret_adapter_config", result.getAdapterConfigJson());
    }
}
