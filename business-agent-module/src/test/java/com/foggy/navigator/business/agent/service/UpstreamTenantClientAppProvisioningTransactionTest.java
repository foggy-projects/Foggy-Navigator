package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.dto.UpstreamTenantClientAppProvisioningDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.repository.BusinessAgentDirectoryBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessAgentModelBindingRepository;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.business.agent.repository.ClientAppModelConfigGrantRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.service.worker.PhysicalWorkerRuntimeRegistry;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.common.repository.WorkingDirectoryRepository;
import com.foggy.navigator.spi.config.LlmModelManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@SpringJUnitConfig(UpstreamTenantClientAppProvisioningTransactionTest.TestConfig.class)
class UpstreamTenantClientAppProvisioningTransactionTest {

    private final Map<String, ClientAppEntity> clientAppsByUpstreamKey = new HashMap<>();
    private final Map<String, CodingAgentEntity> agentsByKey = new HashMap<>();

    @jakarta.annotation.Resource
    private ClientAppRepository clientAppRepository;
    @jakarta.annotation.Resource
    private BusinessCodingAgentRepository agentRepository;
    @jakarta.annotation.Resource
    private ClientAppService clientAppService;
    @jakarta.annotation.Resource
    private TransactionalEnsureCaller caller;

    @BeforeEach
    void setUp() {
        clientAppsByUpstreamKey.clear();
        agentsByKey.clear();
        reset(clientAppRepository, agentRepository, clientAppService);
        when(clientAppRepository.findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                anyString(), anyString(), anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(clientAppsByUpstreamKey.get(upstreamKey(
                        inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(3)))));
        when(clientAppRepository.save(any())).thenAnswer(inv -> {
            ClientAppEntity app = inv.getArgument(0);
            clientAppsByUpstreamKey.put(upstreamKey(app.getTenantId(), app.getUpstreamSystemId(),
                    app.getUpstreamClientAppNamespace(), app.getUpstreamRef()), app);
            return app;
        });
        when(agentRepository.findByAgentIdAndTenantId(anyString(), anyString()))
                .thenAnswer(inv -> Optional.ofNullable(agentsByKey.get(agentKey(inv.getArgument(0), inv.getArgument(1)))));
        when(agentRepository.save(any())).thenAnswer(inv -> {
            CodingAgentEntity agent = inv.getArgument(0);
            agentsByKey.put(agentKey(agent.getAgentId(), agent.getTenantId()), agent);
            return agent;
        });
        when(clientAppService.issueRuntimeCredential(anyString(), anyString(), any())).thenAnswer(inv -> {
            IssuedCredentialDTO dto = new IssuedCredentialDTO();
            dto.setTenantId(inv.getArgument(0));
            dto.setClientAppId(inv.getArgument(1));
            dto.setAppKey("cak-secret");
            dto.setSecret("cas-secret");
            return dto;
        });
        when(clientAppService.issueControlCredential(anyString(), anyString(), anyString(), any())).thenAnswer(inv -> {
            IssuedCredentialDTO dto = new IssuedCredentialDTO();
            dto.setTenantId(inv.getArgument(0));
            dto.setClientAppId(inv.getArgument(2));
            dto.setControlApiKey("cac-secret");
            return dto;
        });
    }

    @Test
    void ensureReturnsStructuredNotReadyWhenReadinessChecksRollbackTheirOwnTransactions() {
        UpstreamTenantClientAppProvisioningDTO result = assertDoesNotThrow(
                () -> caller.ensure(form(), principal()));

        assertFalse(result.isActivationReady());
        assertEquals(UpstreamTenantClientAppProvisioningService.ERROR_MODEL_CONFIG_RESOURCE, result.getErrorCode());
        assertTrue(result.getMissingFields().contains("modelConfig.visibility"));
        assertTrue(result.getMissingFields().contains("directory.tenant"));
        assertTrue(result.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("model config is not visible to this ClientApp")));
        assertTrue(result.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("root agent was ensured without defaultModelConfigId")));
        assertTrue(result.getBlockers().stream()
                .anyMatch(blocker -> blocker.contains("working directory tenant mismatch: 20260525-8fa8")));
        assertNotNull(result.getRemediationHint());
    }

    private EnsureUpstreamTenantClientAppForm form() {
        EnsureUpstreamTenantClientAppForm form = new EnsureUpstreamTenantClientAppForm();
        form.setSourceSystem("TMS");
        form.setSourceTenantId("138");
        form.setClientAppName("tms-tenant-138");
        form.setTenantName("TMS tenant 138");
        form.setCapabilityDomain("tms-x3-tenant-138");
        form.setModelConfigId("legacy-model-config");
        form.setAgentCode("tms-tenant-138-root-agent");
        form.setPhysicalWorkerId("biz-worker-138");
        form.setDirectoryId("20260525-8fa8");
        form.setRotateCredentials(true);
        return form;
    }

    private UpstreamClientAppAdminPrincipal principal() {
        return UpstreamClientAppAdminPrincipal.builder()
                .credentialId("ucaac-tenant-138")
                .principalId("admin")
                .upstreamSystemId("TMS")
                .authorizedClientAppNamespace("TMS")
                .authorizedTenantIds(Set.of("nav_tms_138", "TMS:138"))
                .scopes(Set.of(
                        UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE,
                        UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE))
                .build();
    }

    private String upstreamKey(String tenantId, String upstreamSystemId, String namespace, String upstreamRef) {
        return tenantId + "|" + upstreamSystemId + "|" + namespace + "|" + upstreamRef;
    }

    private String agentKey(String agentId, String tenantId) {
        return tenantId + "|" + agentId;
    }

    public static class TransactionalEnsureCaller {
        private final UpstreamTenantClientAppProvisioningService service;

        TransactionalEnsureCaller(UpstreamTenantClientAppProvisioningService service) {
            this.service = service;
        }

        @Transactional
        public UpstreamTenantClientAppProvisioningDTO ensure(EnsureUpstreamTenantClientAppForm form,
                                                             UpstreamClientAppAdminPrincipal principal) {
            return service.ensure(form, principal);
        }
    }

    public static class RollbackingModelConfigGrantService extends ClientAppModelConfigGrantService {
        RollbackingModelConfigGrantService() {
            super(mock(ClientAppModelConfigGrantRepository.class), mock(ClientAppService.class), mock(LlmModelManager.class));
        }

        @Override
        @Transactional(readOnly = true)
        public List<ClientAppModelConfigGrantDTO> listGrants(String tenantId, String clientAppId) {
            return List.of();
        }

        @Override
        @Transactional
        public ClientAppModelConfigGrantDTO grantModelConfig(String tenantId, String actorUserId,
                                                             String clientAppId, GrantModelConfigForm form) {
            throw new IllegalArgumentException("model config is not visible to this ClientApp");
        }
    }

    public static class RollbackingAgentResourceResolver extends A2AgentResourceResolver {
        RollbackingAgentResourceResolver(BusinessCodingAgentRepository agentRepository) {
            super(
                    mock(ClientAppModelConfigGrantService.class),
                    mock(LlmModelManager.class),
                    mock(ClientAppService.class),
                    mock(WorkingDirectoryRepository.class),
                    agentRepository,
                    mock(BizWorkerPoolRepository.class),
                    mock(BizWorkerIdentityRepository.class),
                    List.<PhysicalWorkerRuntimeRegistry>of(),
                    mock(BusinessAgentDirectoryBindingRepository.class),
                    mock(BusinessAgentModelBindingRepository.class));
        }

        @Override
        @Transactional(readOnly = true)
        public ResolvedAgentResource resolveRequiredAgent(String tenantId,
                                                          String clientAppId,
                                                          String upstreamUserId,
                                                          String agentId) {
            return new ResolvedAgentResource(
                    agentId,
                    ResourceOwnerType.CLIENT_APP,
                    clientAppId,
                    clientAppId,
                    "tms.navigator.agent",
                    null,
                    null,
                    null,
                    null,
                    ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND,
                    "biz-worker-138",
                    ResourceOwnerType.UPSTREAM_SYSTEM,
                    "TMS",
                    "PHYSICAL_WORKER_IDENTITY:UPSTREAM_SYSTEM",
                    null,
                    null,
                    "20260525-8fa8",
                    "AGENT:CLIENT_APP");
        }

        @Override
        @Transactional(readOnly = true)
        public ResolvedWorkspaceResource resolveRequiredWorkspaceForAgent(String tenantId,
                                                                         String clientAppId,
                                                                         String upstreamUserId,
                                                                         ResolvedAgentResource agentResource,
                                                                         String directoryId) {
            throw new SecurityException("working directory tenant mismatch: " + directoryId);
        }
    }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    static class TestConfig {
        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource("jdbc:h2:mem:tenant_ensure_tx;DB_CLOSE_DELAY=-1", "sa", "");
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        ClientAppRepository clientAppRepository() {
            return mock(ClientAppRepository.class);
        }

        @Bean
        BusinessCodingAgentRepository agentRepository() {
            return mock(BusinessCodingAgentRepository.class);
        }

        @Bean
        ClientAppService clientAppService() {
            return mock(ClientAppService.class);
        }

        @Bean
        ClientAppModelConfigGrantService modelConfigGrantService() {
            return new RollbackingModelConfigGrantService();
        }

        @Bean
        SkillRegistryService skillRegistryService() {
            return mock(SkillRegistryService.class);
        }

        @Bean
        AgentDefaultBindingService agentDefaultBindingService() {
            return mock(AgentDefaultBindingService.class);
        }

        @Bean
        A2AgentResourceResolver agentResourceResolver(BusinessCodingAgentRepository agentRepository) {
            return new RollbackingAgentResourceResolver(agentRepository);
        }

        @Bean
        UpstreamTenantClientAppProvisioningService upstreamTenantClientAppProvisioningService(
                ClientAppRepository clientAppRepository,
                ClientAppService clientAppService,
                ClientAppModelConfigGrantService modelConfigGrantService,
                BusinessCodingAgentRepository agentRepository,
                SkillRegistryService skillRegistryService,
                AgentDefaultBindingService agentDefaultBindingService,
                A2AgentResourceResolver agentResourceResolver) {
            return new UpstreamTenantClientAppProvisioningService(
                    clientAppRepository,
                    clientAppService,
                    modelConfigGrantService,
                    agentRepository,
                    skillRegistryService,
                    agentDefaultBindingService,
                    agentResourceResolver,
                    new ObjectMapper());
        }

        @Bean
        TransactionalEnsureCaller transactionalEnsureCaller(UpstreamTenantClientAppProvisioningService service) {
            return new TransactionalEnsureCaller(service);
        }
    }
}
