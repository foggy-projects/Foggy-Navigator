package com.foggy.navigator.langgraph.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerIdentityRepository;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.business.agent.service.ClientAppModelConfigGrantService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphWorkerHealthDTO;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphWorkerRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
public class LanggraphWorkerService {

    private static final TypeReference<Map<String, Object>> PROVIDER_EXT_TYPE = new TypeReference<>() {};

    private final LanggraphWorkerRepository workerRepository;
    private final BizWorkerIdentityRepository workerIdentityRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${navigator.langgraph.worker.connect-timeout-ms:10000}")
    private long connectTimeoutMillis = 10_000;

    @Value("${navigator.langgraph.worker.response-timeout-seconds:1800}")
    private long responseTimeoutSeconds = 1_800;

    @Value("${navigator.langgraph.worker.default-worker-id:}")
    private String defaultWorkerId;

    public LanggraphWorkerService(LanggraphWorkerRepository workerRepository) {
        this(workerRepository, null);
    }

    @Autowired
    public LanggraphWorkerService(LanggraphWorkerRepository workerRepository,
                                  BizWorkerIdentityRepository workerIdentityRepository) {
        this.workerRepository = workerRepository;
        this.workerIdentityRepository = workerIdentityRepository;
    }

    public LanggraphWorkerEntity getWorkerEntity(String workerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        // A governed identity owns its globally unique workerId. Prefer it on
        // collisions so later task/stream lookups cannot switch a route that
        // was authorized from BizWorkerIdentity back to a legacy endpoint.
        // Legacy-only workerIds retain their existing resolution behavior.
        return findIdentityBackedWorker(normalizedWorkerId)
                .or(() -> workerRepository.findByWorkerId(normalizedWorkerId))
                .orElseThrow(() -> new IllegalArgumentException("LangGraph worker not found: " + workerId));
    }

    /**
     * Resolves a Business Agent runtime route exclusively from the governed
     * {@link BizWorkerIdentityEntity}. A same-named legacy LangGraph worker is
     * deliberately ignored so it cannot replace the pool-selected identity's
     * endpoint or capabilities.
     *
     * <p>When a real pool owns the route, an upstream-system identity must
     * match that pool owner exactly. Platform identities retain the existing
     * shared-infrastructure exception, but only for the canonical
     * {@code (PLATFORM, platform)} owner. With no real pool (physical-only
     * compatibility), only that canonical platform identity is trusted.</p>
     */
    public LanggraphWorkerEntity getBusinessAgentWorkerEntity(
            String workerId,
            ResourceOwnerType poolOwnerType,
            String poolOwnerId) {
        String normalizedWorkerId = requireWorkerId(workerId);
        if (workerIdentityRepository == null) {
            throw new IllegalStateException("BizWorker identity registry is not available");
        }
        BizWorkerIdentityEntity identity = workerIdentityRepository.findByWorkerId(normalizedWorkerId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "LangGraph BizWorker identity not found: " + normalizedWorkerId));
        requireLanggraphBizBackend(identity);
        requireBusinessAgentIdentityVisibility(identity, poolOwnerType, poolOwnerId);
        return toRuntimeWorker(identity);
    }

    public List<LanggraphWorkerEntity> listWorkers(String userId) {
        return workerRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public String resolveTaskWorkerId(String preferredWorkerId) {
        if (StringUtils.hasText(preferredWorkerId)) {
            String workerId = preferredWorkerId.trim();
            Optional<LanggraphWorkerEntity> worker = findIdentityBackedWorker(workerId)
                    .or(() -> workerRepository.findByWorkerId(workerId));
            if (worker.isPresent()) {
                return worker.get().getWorkerId();
            }
            log.warn("Configured LangGraph workerId {} is not registered; falling back to default BizWorker", workerId);
        }
        return resolveDefaultWorker().getWorkerId();
    }

    public LanggraphWorkerEntity resolveDefaultWorker() {
        if (StringUtils.hasText(defaultWorkerId)) {
            String workerId = defaultWorkerId.trim();
            try {
                return getWorkerEntity(workerId);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("Configured default LangGraph worker not found: " + workerId, ex);
            }
        }

        List<LanggraphWorkerEntity> workers = workerRepository.findAll(Sort.by(Sort.Direction.ASC, "createdAt"));
        if (workers.isEmpty()) {
            throw new IllegalStateException("No LangGraph BizWorker is registered; register one worker or set navigator.langgraph.worker.default-worker-id");
        }
        if (workers.size() == 1) {
            return workers.get(0);
        }

        List<LanggraphWorkerEntity> onlineWorkers = workers.stream()
                .filter(worker -> "ONLINE".equals(worker.getStatus()))
                .toList();
        if (onlineWorkers.size() == 1) {
            return onlineWorkers.get(0);
        }
        throw new IllegalStateException("Multiple LangGraph BizWorkers are registered; set navigator.langgraph.worker.default-worker-id");
    }

    public LanggraphWorkerClient createClient(LanggraphWorkerEntity worker) {
        return new LanggraphWorkerClient(
                worker.getWorkerId(),
                worker.getBaseUrl(),
                worker.getAuthToken(),
                Duration.ofMillis(Math.max(1, connectTimeoutMillis)),
                Duration.ofSeconds(Math.max(1, responseTimeoutSeconds))
        );
    }

    public void applyHealthSnapshot(LanggraphWorkerEntity worker, LanggraphWorkerHealthDTO health) {
        if (worker == null) {
            throw new IllegalArgumentException("LangGraph worker is required");
        }
        if (health == null) {
            worker.setStatus("OFFLINE");
            return;
        }
        // Missing `ready` keeps legacy Worker compatibility. An explicit false
        // must never be promoted to ONLINE merely because /health returned 200.
        worker.setStatus(Boolean.FALSE.equals(health.getReady()) ? "OFFLINE" : "ONLINE");
        if (StringUtils.hasText(health.getHostname())) {
            worker.setHostname(health.getHostname().trim());
        }
        if (StringUtils.hasText(health.getVersion())) {
            worker.setWorkerVersion(health.getVersion().trim());
        }
        if (health.getCapabilities() != null) {
            worker.setProviderExt(mergeProviderExtCapabilities(worker.getProviderExt(), health.getCapabilities()));
        }
    }

    private Optional<LanggraphWorkerEntity> findIdentityBackedWorker(String workerId) {
        if (workerIdentityRepository == null || !StringUtils.hasText(workerId)) {
            return Optional.empty();
        }
        return workerIdentityRepository.findByWorkerId(workerId.trim())
                .filter(this::isLanggraphBizIdentity)
                .map(this::toRuntimeWorker);
    }

    private boolean isLanggraphBizIdentity(BizWorkerIdentityEntity worker) {
        if (worker == null || !StringUtils.hasText(worker.getWorkerBackend())) {
            return false;
        }
        return ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND.equals(worker.getWorkerBackend().trim());
    }

    private void requireLanggraphBizBackend(BizWorkerIdentityEntity identity) {
        String backend = StringUtils.hasText(identity.getWorkerBackend())
                ? identity.getWorkerBackend().trim()
                : null;
        if (!ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND.equals(backend)) {
            throw new IllegalStateException(
                    "BizWorker identity backend mismatch: " + identity.getWorkerId());
        }
    }

    private void requireBusinessAgentIdentityVisibility(
            BizWorkerIdentityEntity identity,
            ResourceOwnerType poolOwnerType,
            String poolOwnerId) {
        if (identity.getOwnerType() == null || !StringUtils.hasText(identity.getOwnerId())) {
            throw new IllegalStateException(
                    "LangGraph worker identity owner is not configured: " + identity.getWorkerId());
        }

        String identityOwnerId = identity.getOwnerId().trim();
        if (identity.getOwnerType() == ResourceOwnerType.PLATFORM) {
            if (!BizWorkerPoolService.PLATFORM_OWNER_ID.equals(identityOwnerId)) {
                throw new SecurityException(
                        "LangGraph platform worker identity owner mismatch: " + identity.getWorkerId());
            }
            return;
        }

        if (identity.getOwnerType() != ResourceOwnerType.UPSTREAM_SYSTEM
                || poolOwnerType != ResourceOwnerType.UPSTREAM_SYSTEM
                || !StringUtils.hasText(poolOwnerId)
                || !identityOwnerId.equals(poolOwnerId.trim())) {
            throw new SecurityException(
                    "LangGraph worker identity is not visible to worker pool: " + identity.getWorkerId());
        }
    }

    private LanggraphWorkerEntity toRuntimeWorker(BizWorkerIdentityEntity identity) {
        if (!BizWorkerPoolService.STATUS_ENABLED.equals(identity.getStatus())) {
            throw new IllegalStateException("LangGraph worker identity is disabled: " + identity.getWorkerId());
        }
        if (!BizWorkerPoolService.HEALTHY.equals(identity.getHealthStatus())) {
            throw new IllegalStateException("LangGraph worker identity is not healthy: " + identity.getWorkerId());
        }
        if (!StringUtils.hasText(identity.getBaseUrl())) {
            throw new IllegalStateException("LangGraph worker identity baseUrl is not configured: " + identity.getWorkerId());
        }

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(identity.getWorkerId());
        worker.setName("BizWorker " + identity.getWorkerId());
        worker.setBaseUrl(identity.getBaseUrl().trim());
        // BizWorkerIdentity stores only a credential hash. It authenticates
        // inbound Worker -> Gateway calls and must never be sent as an
        // outbound Bearer secret. Internal-dev keeps its existing no-auth
        // Worker client behavior until a dedicated outbound credential exists.
        worker.setAuthToken("");
        worker.setAuthMode("IDENTITY");
        worker.setStatus("ONLINE");
        worker.setWorkerVersion(identity.getVersion());
        worker.setProviderExt(identityProviderExt(identity));
        return worker;
    }

    private String mergeProviderExtCapabilities(
            String providerExt,
            LanggraphWorkerHealthDTO.WorkerCapabilitiesDTO capabilities
    ) {
        Map<String, Object> merged = readProviderExt(providerExt);
        merged.put("capabilities", capabilities);
        return writeProviderExt(merged, providerExt);
    }

    private String identityProviderExt(BizWorkerIdentityEntity identity) {
        Map<String, Object> providerExt = new LinkedHashMap<>();
        providerExt.put("source", "BIZ_WORKER_IDENTITY");
        if (StringUtils.hasText(identity.getCapabilitiesJson())) {
            providerExt.put("capabilities", readCapabilitiesJson(identity.getCapabilitiesJson()));
        }
        return writeProviderExt(providerExt, "{\"source\":\"BIZ_WORKER_IDENTITY\"}");
    }

    private Map<String, Object> readProviderExt(String providerExt) {
        Map<String, Object> parsed = new LinkedHashMap<>();
        if (!StringUtils.hasText(providerExt)) {
            return parsed;
        }
        try {
            Map<String, Object> existing = objectMapper.readValue(providerExt, PROVIDER_EXT_TYPE);
            if (existing != null) {
                parsed.putAll(existing);
            }
        } catch (Exception ex) {
            parsed.put("rawProviderExt", providerExt);
        }
        return parsed;
    }

    private Object readCapabilitiesJson(String capabilitiesJson) {
        try {
            return objectMapper.readValue(capabilitiesJson, PROVIDER_EXT_TYPE);
        } catch (Exception ex) {
            return capabilitiesJson;
        }
    }

    private String writeProviderExt(Map<String, Object> providerExt, String fallback) {
        try {
            return objectMapper.writeValueAsString(providerExt);
        } catch (JsonProcessingException ex) {
            log.warn("Failed to serialize LangGraph worker providerExt: {}", ex.getMessage());
            return fallback;
        }
    }

    private String requireWorkerId(String workerId) {
        if (!StringUtils.hasText(workerId)) {
            throw new IllegalArgumentException("LangGraph workerId is required");
        }
        return workerId.trim();
    }
}
