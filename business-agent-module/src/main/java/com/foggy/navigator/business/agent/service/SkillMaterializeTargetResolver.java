package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SkillMaterializeTargetResolver {

    private static final String BACKEND_LANGGRAPH_BIZ = ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND;

    private final ClientAppService clientAppService;
    private final BusinessCodingAgentRepository agentRepository;
    private final BizWorkerPoolRepository workerPoolRepository;
    private final BizWorkerPoolMemberRepository workerPoolMemberRepository;
    private final BizWorkerIdentityRepository workerIdentityRepository;

    public record Target(String workerId, String baseUrl, String source) {
    }

    @Transactional(readOnly = true)
    public List<Target> resolveTargets(String tenantId, String clientAppId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");

        ClientAppEntity clientApp = clientAppService.requireActiveClientApp(tenantId, clientAppId);
        Map<String, Target> targets = new LinkedHashMap<>();

        for (CodingAgentEntity agent : resolveVisibleAgents(tenantId, clientApp)) {
            collectAgentRouteTarget(tenantId, clientApp, agent, targets);
        }

        if (targets.isEmpty()) {
            collectUpstreamSystemWorkerIdentities(clientApp, targets);
        }

        return List.copyOf(targets.values());
    }

    private List<CodingAgentEntity> resolveVisibleAgents(String tenantId, ClientAppEntity clientApp) {
        List<CodingAgentEntity> agents = new ArrayList<>();
        List<CodingAgentEntity> clientAppAgents = agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                tenantId,
                ResourceOwnerType.CLIENT_APP,
                clientApp.getClientAppId());
        if (clientAppAgents != null) {
            agents.addAll(clientAppAgents);
        }
        if (StringUtils.hasText(clientApp.getUpstreamSystemId())) {
            List<CodingAgentEntity> upstreamAgents = agentRepository.findByTenantIdAndOwnerTypeAndOwnerIdOrderByCreatedAtDesc(
                    tenantId,
                    ResourceOwnerType.UPSTREAM_SYSTEM,
                    clientApp.getUpstreamSystemId());
            if (upstreamAgents != null) {
                agents.addAll(upstreamAgents);
            }
        }
        return agents;
    }

    private void collectAgentRouteTarget(
            String tenantId,
            ClientAppEntity clientApp,
            CodingAgentEntity agent,
            Map<String, Target> targets) {
        if (agent == null || !Boolean.TRUE.equals(agent.getEnabled())) {
            return;
        }
        String workerRef = trimToNull(agent.getWorkerId());
        if (workerRef == null) {
            return;
        }
        collectWorkerRef(tenantId, clientApp, workerRef, "AGENT_ROUTE:" + agent.getAgentId(), targets);
    }

    private void collectWorkerRef(
            String tenantId,
            ClientAppEntity clientApp,
            String workerRef,
            String source,
            Map<String, Target> targets) {
        Optional<BizWorkerPoolEntity> pool = workerPoolRepository.findByPoolIdAndTenantId(workerRef, tenantId);
        if (pool.isPresent()) {
            collectPoolTargets(clientApp, pool.get(), source, targets);
            return;
        }

        workerIdentityRepository.findByWorkerId(workerRef)
                .ifPresent(worker -> collectWorkerIdentity(clientApp, worker, source + ":WORKER_IDENTITY", targets));
    }

    private void collectPoolTargets(
            ClientAppEntity clientApp,
            BizWorkerPoolEntity pool,
            String source,
            Map<String, Target> targets) {
        if (!isAvailableLanggraphPool(clientApp, pool)) {
            return;
        }
        List<BizWorkerPoolMemberEntity> members = workerPoolMemberRepository.findByPoolIdOrderByCreatedAtAsc(pool.getPoolId());
        if (members == null) {
            return;
        }
        for (BizWorkerPoolMemberEntity member : members) {
            if (member == null || !BizWorkerPoolService.STATUS_ENABLED.equals(member.getStatus())) {
                continue;
            }
            workerIdentityRepository.findByWorkerId(member.getWorkerId())
                    .ifPresent(worker -> collectWorkerIdentity(
                            clientApp,
                            worker,
                            source + ":WORKER_POOL:" + pool.getPoolId(),
                            targets));
        }
    }

    private void collectUpstreamSystemWorkerIdentities(ClientAppEntity clientApp, Map<String, Target> targets) {
        if (!StringUtils.hasText(clientApp.getUpstreamSystemId())) {
            return;
        }
        List<BizWorkerIdentityEntity> identities = workerIdentityRepository
                .findByOwnerTypeAndOwnerIdAndWorkerBackendAndStatusAndHealthStatusOrderByUpdatedAtDesc(
                        ResourceOwnerType.UPSTREAM_SYSTEM,
                        clientApp.getUpstreamSystemId(),
                        BACKEND_LANGGRAPH_BIZ,
                        BizWorkerPoolService.STATUS_ENABLED,
                        BizWorkerPoolService.HEALTHY);
        if (identities == null) {
            return;
        }
        for (BizWorkerIdentityEntity identity : identities) {
            collectWorkerIdentity(clientApp, identity, "UPSTREAM_SYSTEM_WORKER_IDENTITY", targets);
        }
    }

    private void collectWorkerIdentity(
            ClientAppEntity clientApp,
            BizWorkerIdentityEntity worker,
            String source,
            Map<String, Target> targets) {
        if (!isAvailableLanggraphWorker(clientApp, worker)) {
            return;
        }
        String baseUrl = trimToNull(worker.getBaseUrl());
        if (baseUrl == null) {
            return;
        }
        String workerId = trimToNull(worker.getWorkerId());
        String key = workerId != null ? "worker:" + workerId : "url:" + baseUrl;
        targets.putIfAbsent(key, new Target(workerId, baseUrl, source));
    }

    private boolean isAvailableLanggraphPool(ClientAppEntity clientApp, BizWorkerPoolEntity pool) {
        if (pool == null) {
            return false;
        }
        if (!BACKEND_LANGGRAPH_BIZ.equals(trimToNull(pool.getWorkerBackend()))) {
            return false;
        }
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(pool.getStatus())
                || !BizWorkerPoolService.HEALTHY.equals(pool.getHealthStatus())) {
            return false;
        }
        return isOwnerVisibleToClientApp(clientApp, pool.getOwnerType(), pool.getOwnerId());
    }

    private boolean isAvailableLanggraphWorker(ClientAppEntity clientApp, BizWorkerIdentityEntity worker) {
        if (worker == null) {
            return false;
        }
        if (!BACKEND_LANGGRAPH_BIZ.equals(trimToNull(worker.getWorkerBackend()))) {
            return false;
        }
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(worker.getStatus())
                || !BizWorkerPoolService.HEALTHY.equals(worker.getHealthStatus())) {
            return false;
        }
        return isOwnerVisibleToClientApp(clientApp, worker.getOwnerType(), worker.getOwnerId());
    }

    private boolean isOwnerVisibleToClientApp(ClientAppEntity clientApp, ResourceOwnerType ownerType, String ownerId) {
        if (clientApp == null || ownerType == null || !StringUtils.hasText(ownerId)) {
            return false;
        }
        return switch (ownerType) {
            case PLATFORM -> true;
            case UPSTREAM_SYSTEM -> StringUtils.hasText(clientApp.getUpstreamSystemId())
                    && clientApp.getUpstreamSystemId().equals(ownerId);
            case CLIENT_APP -> clientApp.getClientAppId().equals(ownerId);
            case UPSTREAM_USER -> false;
        };
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
