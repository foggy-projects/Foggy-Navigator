package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphWorkerHealthDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanggraphWorkerServiceTest {

    @Mock
    private LanggraphWorkerRepository workerRepository;

    @Mock
    private BizWorkerIdentityRepository workerIdentityRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LanggraphWorkerService service;

    @BeforeEach
    void setUp() {
        service = new LanggraphWorkerService(workerRepository);
    }

    @Test
    void resolveTaskWorkerIdReturnsPreferredWorkerWhenRegistered() {
        when(workerRepository.findByWorkerId("worker_01"))
                .thenReturn(Optional.of(worker("worker_01", "UNKNOWN")));

        assertEquals("worker_01", service.resolveTaskWorkerId("worker_01"));
    }

    @Test
    void resolveTaskWorkerIdFallsBackToOnlyRegisteredWorkerForMissingPreferredWorker() {
        when(workerRepository.findByWorkerId("old_pool_id")).thenReturn(Optional.empty());
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of(worker("worker_01", "UNKNOWN")));

        assertEquals("worker_01", service.resolveTaskWorkerId("old_pool_id"));
    }

    @Test
    void resolveDefaultWorkerUsesConfiguredWorkerId() {
        ReflectionTestUtils.setField(service, "defaultWorkerId", "worker_cfg");
        when(workerRepository.findByWorkerId("worker_cfg"))
                .thenReturn(Optional.of(worker("worker_cfg", "ONLINE")));

        assertEquals("worker_cfg", service.resolveDefaultWorker().getWorkerId());
    }

    @Test
    void resolveDefaultWorkerUsesSingleOnlineWorkerWhenMultipleWorkersExist() {
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of(
                worker("worker_01", "OFFLINE"),
                worker("worker_02", "ONLINE")));

        assertEquals("worker_02", service.resolveDefaultWorker().getWorkerId());
    }

    @Test
    void resolveDefaultWorkerRejectsMissingWorker() {
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of());

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDefaultWorker());

        assertTrue(error.getMessage().contains("No LangGraph BizWorker"));
    }

    @Test
    void resolveDefaultWorkerRejectsMultipleWorkersWithoutUniqueOnlineWorker() {
        when(workerRepository.findAll(any(Sort.class))).thenReturn(List.of(
                worker("worker_01", "UNKNOWN"),
                worker("worker_02", "UNKNOWN")));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> service.resolveDefaultWorker());

        assertTrue(error.getMessage().contains("Multiple LangGraph BizWorkers"));
    }

    @Test
    void getWorkerEntityReturnsIdentityBackedWorkerWhenLegacyEntityIsMissing() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        LanggraphWorkerEntity worker = serviceWithIdentity.getWorkerEntity("biz_worker_01");

        assertEquals("biz_worker_01", worker.getWorkerId());
        assertEquals("http://127.0.0.1:3161", worker.getBaseUrl());
        assertEquals("IDENTITY", worker.getAuthMode());
        assertEquals("ONLINE", worker.getStatus());
    }

    @Test
    void getWorkerEntityReturnsIdentityCapabilitiesInProviderExt() throws Exception {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setCapabilitiesJson("{\"agent_delegation\":{\"nested_agent_delegation_allowed\":false}}");
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        LanggraphWorkerEntity worker = serviceWithIdentity.getWorkerEntity("biz_worker_01");

        Map<String, Object> providerExt = objectMapper.readValue(worker.getProviderExt(),
                new TypeReference<>() {});
        assertEquals("BIZ_WORKER_IDENTITY", providerExt.get("source"));
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) providerExt.get("capabilities");
        @SuppressWarnings("unchecked")
        Map<String, Object> agentDelegation = (Map<String, Object>) capabilities.get("agent_delegation");
        assertEquals(false, agentDelegation.get("nested_agent_delegation_allowed"));
    }

    @Test
    void getBusinessAgentWorkerEntityIgnoresSameNamedLegacyWorker() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        LanggraphWorkerEntity legacy = worker("biz_worker_01", "ONLINE");
        legacy.setBaseUrl("http://legacy.invalid:9999");
        legacy.setAuthToken("legacy-secret");
        lenient().when(workerRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(legacy));
        BizWorkerIdentityEntity identity = identity(
                "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setBaseUrl("http://127.0.0.1:3161");
        identity.setTokenHash("sha256-digest-not-a-bearer-secret");
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        LanggraphWorkerEntity resolved = serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_01");

        assertEquals("http://127.0.0.1:3161", resolved.getBaseUrl());
        assertEquals("", resolved.getAuthToken());
        assertEquals("IDENTITY", resolved.getAuthMode());
        verify(workerRepository, never()).findByWorkerId("biz_worker_01");
    }

    @Test
    void getWorkerEntityKeepsGovernedIdentityPrecedenceAfterLaunch() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        LanggraphWorkerEntity legacy = worker("biz_worker_01", "ONLINE");
        legacy.setBaseUrl("http://legacy.invalid:9999");
        lenient().when(workerRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(legacy));
        BizWorkerIdentityEntity identity = identity(
                "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setBaseUrl("http://127.0.0.1:3161");
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        LanggraphWorkerEntity resolved = serviceWithIdentity.getWorkerEntity("biz_worker_01");

        assertEquals("http://127.0.0.1:3161", resolved.getBaseUrl());
        assertEquals("IDENTITY", resolved.getAuthMode());
        verify(workerRepository, never()).findByWorkerId("biz_worker_01");
    }

    @Test
    void getBusinessAgentWorkerEntityAllowsCanonicalPlatformIdentityForUpstreamPool() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity(
                "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setOwnerType(ResourceOwnerType.PLATFORM);
        identity.setOwnerId(BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        LanggraphWorkerEntity resolved = serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_01");

        assertEquals("biz_worker_01", resolved.getWorkerId());
    }

    @Test
    void getBusinessAgentWorkerEntityAllowsCanonicalPlatformIdentityOnPhysicalOnlyRoute() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity(
                "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setOwnerType(ResourceOwnerType.PLATFORM);
        identity.setOwnerId(BizWorkerPoolService.PLATFORM_OWNER_ID);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        LanggraphWorkerEntity resolved = serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", null, null);

        assertEquals("biz_worker_01", resolved.getWorkerId());
    }

    @Test
    void getBusinessAgentWorkerEntityRejectsNonCanonicalPlatformIdentityOwner() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity(
                "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setOwnerType(ResourceOwnerType.PLATFORM);
        identity.setOwnerId("tenant_01");
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        assertThrows(SecurityException.class, () -> serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", ResourceOwnerType.PLATFORM, "tenant_01"));
    }

    @Test
    void getBusinessAgentWorkerEntityRejectsUpstreamOwnerMismatch() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity(
                        "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        assertThrows(SecurityException.class, () -> serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_other"));
    }

    @Test
    void getBusinessAgentWorkerEntityRejectsUpstreamIdentityOnPhysicalOnlyRoute() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity(
                        "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        assertThrows(SecurityException.class, () -> serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", null, null));
    }

    @Test
    void getBusinessAgentWorkerEntityRejectsBackendMismatch() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", "CODEX")));

        assertThrows(IllegalStateException.class, () -> serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_01"));
    }

    @Test
    void getBusinessAgentWorkerEntityRejectsUnhealthyIdentity() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity(
                "biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setHealthStatus(BizWorkerPoolService.UNHEALTHY);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity));

        assertThrows(IllegalStateException.class, () -> serviceWithIdentity.getBusinessAgentWorkerEntity(
                "biz_worker_01", ResourceOwnerType.UPSTREAM_SYSTEM, "ups_01"));
    }

    @Test
    void resolveTaskWorkerIdReturnsPreferredIdentityWorkerWhenLegacyEntityIsMissing() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        assertEquals("biz_worker_01", serviceWithIdentity.resolveTaskWorkerId("biz_worker_01"));
    }

    @Test
    void resolveDefaultWorkerSupportsConfiguredIdentityWorkerId() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        ReflectionTestUtils.setField(serviceWithIdentity, "defaultWorkerId", "biz_worker_01");
        when(workerIdentityRepository.findByWorkerId("biz_worker_01"))
                .thenReturn(Optional.of(identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND)));

        assertEquals("biz_worker_01", serviceWithIdentity.resolveDefaultWorker().getWorkerId());
    }

    @Test
    void getWorkerEntityRejectsDisabledIdentityBackedWorker() {
        LanggraphWorkerService serviceWithIdentity =
                new LanggraphWorkerService(workerRepository, workerIdentityRepository);
        BizWorkerIdentityEntity identity = identity("biz_worker_01", ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND);
        identity.setStatus(BizWorkerPoolService.STATUS_DISABLED);
        when(workerIdentityRepository.findByWorkerId("biz_worker_01")).thenReturn(Optional.of(identity));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> serviceWithIdentity.getWorkerEntity("biz_worker_01"));

        assertTrue(error.getMessage().contains("disabled"));
    }

    @Test
    void applyHealthSnapshotMergesAgentDelegationCapabilitiesIntoProviderExt() throws Exception {
        LanggraphWorkerEntity worker = worker("worker_01", "UNKNOWN");
        worker.setProviderExt("{\"source\":\"manual\"}");

        service.applyHealthSnapshot(worker, healthWithAgentDelegationCapabilities());

        assertEquals("ONLINE", worker.getStatus());
        assertEquals("worker-host-1", worker.getHostname());
        assertEquals("1.0.0", worker.getWorkerVersion());
        Map<String, Object> providerExt = objectMapper.readValue(worker.getProviderExt(),
                new TypeReference<>() {});
        assertEquals("manual", providerExt.get("source"));
        @SuppressWarnings("unchecked")
        Map<String, Object> capabilities = (Map<String, Object>) providerExt.get("capabilities");
        @SuppressWarnings("unchecked")
        Map<String, Object> agentDelegation = (Map<String, Object>) capabilities.get("agent_delegation");
        assertEquals("agent-delegation.v1", agentDelegation.get("contract_version"));
        assertEquals(1, agentDelegation.get("max_agent_nesting_depth"));
        assertEquals(false, agentDelegation.get("nested_agent_delegation_allowed"));
        @SuppressWarnings("unchecked")
        Map<String, Object> tools = (Map<String, Object>) agentDelegation.get("tools");
        @SuppressWarnings("unchecked")
        Map<String, Object> spawnAgent = (Map<String, Object>) tools.get("spawn_agent");
        assertEquals("invoke_business_agent", spawnAgent.get("tool_name"));
    }

    @Test
    void applyHealthSnapshotDoesNotPromoteExplicitlyUnreadyWorker() {
        LanggraphWorkerEntity worker = worker("worker_01", "UNKNOWN");
        LanggraphWorkerHealthDTO health = new LanggraphWorkerHealthDTO();
        health.setReady(false);
        health.setMode("external-enabled");
        health.setReasons(List.of("EXTERNAL_EXECUTION_POLICY_PENDING"));
        health.setHostname("external-worker-host");

        service.applyHealthSnapshot(worker, health);

        assertEquals("OFFLINE", worker.getStatus());
        assertEquals("external-worker-host", worker.getHostname());
    }

    @Test
    void applyHealthSnapshotTreatsMissingResponseAsOffline() {
        LanggraphWorkerEntity worker = worker("worker_01", "ONLINE");

        service.applyHealthSnapshot(worker, null);

        assertEquals("OFFLINE", worker.getStatus());
    }

    private LanggraphWorkerEntity worker(String workerId, String status) {
        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(workerId);
        worker.setStatus(status);
        return worker;
    }

    private BizWorkerIdentityEntity identity(String workerId, String workerBackend) {
        BizWorkerIdentityEntity identity = new BizWorkerIdentityEntity();
        identity.setWorkerId(workerId);
        identity.setOwnerType(ResourceOwnerType.UPSTREAM_SYSTEM);
        identity.setOwnerId("ups_01");
        identity.setWorkerBackend(workerBackend);
        identity.setBaseUrl("http://127.0.0.1:3161");
        identity.setVersion("1.0.8");
        identity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        identity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        return identity;
    }

    private LanggraphWorkerHealthDTO healthWithAgentDelegationCapabilities() {
        LanggraphWorkerHealthDTO health = new LanggraphWorkerHealthDTO();
        health.setHostname("worker-host-1");
        health.setVersion("1.0.0");
        LanggraphWorkerHealthDTO.WorkerCapabilitiesDTO capabilities =
                new LanggraphWorkerHealthDTO.WorkerCapabilitiesDTO();
        LanggraphWorkerHealthDTO.AgentDelegationCapabilitiesDTO agentDelegation =
                new LanggraphWorkerHealthDTO.AgentDelegationCapabilitiesDTO();
        agentDelegation.setContractVersion("agent-delegation.v1");
        agentDelegation.setMaxAgentNestingDepth(1);
        agentDelegation.setRootAgentDepth(0);
        agentDelegation.setRootAgentDelegationAllowed(true);
        agentDelegation.setNestedAgentDelegationAllowed(false);
        agentDelegation.setChildAgentInheritsParentTools(false);
        agentDelegation.setExplicitNestedAgentAuthorizationRequired(true);
        agentDelegation.setNestedAgentAuthorizationGates(List.of(
                "agent_manifest.allowed_tools",
                "execution_policy.allowed_tools",
                "runtime.max_agent_nesting_depth"
        ));
        LanggraphWorkerHealthDTO.AgentDelegationToolCapabilityDTO spawnAgent =
                new LanggraphWorkerHealthDTO.AgentDelegationToolCapabilityDTO();
        spawnAgent.setSupported(true);
        spawnAgent.setToolName("invoke_business_agent");
        spawnAgent.setMode("open_child_agent_and_sync_wait");
        agentDelegation.setTools(new LinkedHashMap<>(Map.of("spawn_agent", spawnAgent)));
        capabilities.setAgentDelegation(agentDelegation);
        health.setCapabilities(capabilities);
        return health;
    }
}
