package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppSkillGrantDTO;
import com.foggy.navigator.business.agent.model.dto.SkillClearResultDTO;
import com.foggy.navigator.business.agent.model.dto.SkillBundleDTO;
import com.foggy.navigator.business.agent.model.dto.SkillDTO;
import com.foggy.navigator.business.agent.model.dto.SkillFunctionAllowlistDTO;
import com.foggy.navigator.business.agent.model.dto.SkillMaterializeResultDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import com.foggy.navigator.business.agent.model.entity.SkillBundleEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm;
import com.foggy.navigator.business.agent.model.form.ClearSkillBundleForm;
import com.foggy.navigator.business.agent.model.form.CreateSkillForm;
import com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm;
import com.foggy.navigator.business.agent.model.form.SkillBundleFunctionForm;
import com.foggy.navigator.business.agent.model.form.SkillResourceForm;
import com.foggy.navigator.business.agent.model.form.SyncAccountSkillBundleForm;
import com.foggy.navigator.business.agent.model.form.SyncSkillBundleForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.BusinessFunctionVersionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import com.foggy.navigator.business.agent.repository.ClientAppSkillGrantRepository;
import com.foggy.navigator.business.agent.repository.SkillBundleRepository;
import com.foggy.navigator.business.agent.repository.SkillFunctionAllowlistRepository;
import com.foggy.navigator.business.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService.STATUS_DISABLED;
import static com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService.STATUS_ENABLED;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRegistryService {

    public static final String SCOPE_CLIENT_APP_PUBLIC = "CLIENT_APP_PUBLIC";
    public static final String SCOPE_ACCOUNT_PRIVATE = "ACCOUNT_PRIVATE";

    private static final int MAX_SKILL_RESOURCES = 100;
    private static final int MAX_SKILL_RESOURCE_BYTES = 1024 * 1024;
    private static final Pattern SAFE_RESOURCE_SEGMENT = Pattern.compile("^[a-zA-Z0-9][a-zA-Z0-9._-]*$");

    private final SkillRepository skillRepository;
    private final SkillBundleRepository skillBundleRepository;
    private final SkillFunctionAllowlistRepository allowlistRepository;
    private final ClientAppSkillGrantRepository grantRepository;
    private final ClientAppFunctionGrantRepository functionGrantRepository;
    private final BusinessFunctionRepository functionRepository;
    private final BusinessFunctionVersionRepository versionRepository;
    private final ClientAppService clientAppService;
    private final ClientAppUserGrantService userGrantService;
    private final ObjectMapper objectMapper;

    @Value("${foggy.navigator.business.agent.dev-sync-worker-url:http://localhost:3061}")
    private String devSyncWorkerUrl;

    @Value("${foggy.navigator.business.agent.dev-sync-worker-token:}")
    private String devSyncWorkerToken;

    @Transactional
    public SkillDTO createSkill(String tenantId, String actorUserId, CreateSkillForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getSkillId(), "skillId is required");
        Assert.hasText(form.getName(), "name is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        SkillEntity skill = skillRepository.findByTenantIdAndSkillId(tenantId, form.getSkillId())
                .orElseGet(SkillEntity::new);

        skill.setTenantId(tenantId);
        skill.setSkillId(form.getSkillId());
        skill.setName(form.getName());
        skill.setDescription(form.getDescription());
        skill.setMarkdownBody(form.getMarkdownBody());
        skill.setResourcesJson(serializeResources(form.getResources()));
        skill.setStatus(status);
        if (!StringUtils.hasText(skill.getCreatedBy())) {
            skill.setCreatedBy(actorUserId);
        }

        SkillEntity saved = skillRepository.save(skill);
        triggerGrantedPublicSkillMaterialize(tenantId, saved, "createSkill");
        return SkillDTO.fromEntity(saved);
    }

    @Transactional
    public SkillFunctionAllowlistDTO addFunctionToSkillAllowlist(String tenantId, String skillId, String actorUserId, AddFunctionToSkillForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(skillId, "skillId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getFunctionId(), "functionId is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        // Validate Skill
        SkillEntity skill = skillRepository.findByTenantIdAndSkillId(tenantId, skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        if (!STATUS_ENABLED.equals(skill.getStatus())) {
            throw new IllegalStateException("Skill is disabled");
        }

        // Validate Function
        BusinessFunctionEntity function = functionRepository.findByTenantIdAndFunctionId(tenantId, form.getFunctionId())
                .orElseThrow(() -> new IllegalArgumentException("Function not found: " + form.getFunctionId()));
        if (!STATUS_ENABLED.equals(function.getStatus())) {
            throw new IllegalStateException("Function is disabled");
        }

        SkillFunctionAllowlistEntity allowlist = allowlistRepository.findByTenantIdAndSkillIdAndFunctionId(tenantId, skillId, form.getFunctionId())
                .orElseGet(() -> {
                    SkillFunctionAllowlistEntity entity = new SkillFunctionAllowlistEntity();
                    entity.setAllowlistId(UUID.randomUUID().toString().replace("-", ""));
                    return entity;
                });

        allowlist.setTenantId(tenantId);
        allowlist.setSkillId(skillId);
        allowlist.setFunctionId(form.getFunctionId());
        allowlist.setStatus(status);
        if (!StringUtils.hasText(allowlist.getCreatedBy())) {
            allowlist.setCreatedBy(actorUserId);
        }

        SkillFunctionAllowlistEntity saved = allowlistRepository.save(allowlist);
        triggerGrantedPublicSkillMaterialize(tenantId, skill, "addFunctionToSkillAllowlist");
        return SkillFunctionAllowlistDTO.fromEntity(saved);
    }

    @Transactional
    public ClientAppSkillGrantDTO grantSkillToClientApp(String tenantId, String clientAppId, String actorUserId, GrantSkillToClientAppForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(clientAppId, "clientAppId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getSkillId(), "skillId is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        // Validate App
        ClientAppEntity app = clientAppService.requireActiveClientApp(tenantId, clientAppId);

        // Validate Skill
        SkillEntity skill = skillRepository.findByTenantIdAndSkillId(tenantId, form.getSkillId())
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + form.getSkillId()));
        if (!STATUS_ENABLED.equals(skill.getStatus())) {
            throw new IllegalStateException("Skill is disabled");
        }

        ClientAppSkillGrantEntity grant = grantRepository.findByTenantIdAndClientAppIdAndSkillId(tenantId, clientAppId, form.getSkillId())
                .orElseGet(() -> {
                    ClientAppSkillGrantEntity entity = new ClientAppSkillGrantEntity();
                    entity.setGrantId(UUID.randomUUID().toString().replace("-", ""));
                    return entity;
                });

        grant.setTenantId(tenantId);
        grant.setClientAppId(clientAppId);
        grant.setSkillId(form.getSkillId());
        grant.setStatus(status);
        if (!StringUtils.hasText(grant.getCreatedBy())) {
            grant.setCreatedBy(actorUserId);
        }

        ClientAppSkillGrantEntity saved = grantRepository.save(grant);
        if (STATUS_ENABLED.equals(saved.getStatus())) {
            triggerPublicSkillMaterializeForClientApp(tenantId, skill, clientAppId, "grantSkillToClientApp");
        }
        return ClientAppSkillGrantDTO.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public SkillMaterializeResultDTO materializePublicSkill(String tenantId, String skillId) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(skillId, "skillId is required");

        SkillEntity skill = getSkill(tenantId, skillId);
        return materializePublicSkillInternal(tenantId, skill, null);
    }

    @Transactional
    public SkillBundleDTO syncSkillBundle(String tenantId, String actorUserId, SyncSkillBundleForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getClientAppId(), "clientAppId is required");
        Assert.hasText(form.getSkillId(), "skillId is required");
        Assert.hasText(form.getName(), "name is required");

        String scope = normalizeScope(form.getScope());
        String accountId = normalizeAccountId(scope, form.getAccountId());
        String status = normalizeStatus(form.getStatus());

        clientAppService.requireActiveClientApp(tenantId, form.getClientAppId());
        validateBundleFunctions(tenantId, form.getClientAppId(), form.getFunctions());

        SkillBundleEntity bundle = skillBundleRepository
                .findByTenantIdAndClientAppIdAndScopeAndAccountIdAndSkillId(
                        tenantId, form.getClientAppId(), scope, accountId, form.getSkillId())
                .orElseGet(() -> {
                    SkillBundleEntity entity = new SkillBundleEntity();
                    entity.setBundleId(UUID.randomUUID().toString().replace("-", ""));
                    return entity;
                });

        bundle.setTenantId(tenantId);
        bundle.setClientAppId(form.getClientAppId());
        bundle.setScope(scope);
        bundle.setAccountId(accountId);
        bundle.setSkillId(form.getSkillId());
        bundle.setName(form.getName());
        bundle.setDescription(form.getDescription());
        bundle.setMarkdownBody(form.getMarkdownBody());
        bundle.setResourcesJson(serializeResources(form.getResources()));
        bundle.setFunctionsJson(serializeFunctions(form.getFunctions()));
        bundle.setStatus(status);
        if (!StringUtils.hasText(bundle.getCreatedBy())) {
            bundle.setCreatedBy(actorUserId);
        }

        SkillBundleEntity saved = skillBundleRepository.save(bundle);
        syncLegacySkillIndex(saved, actorUserId);
        syncLegacySkillGrant(saved, actorUserId);
        syncLegacyFunctionAllowlist(saved, actorUserId, form.getFunctions());

        SkillBundleDTO dto = SkillBundleDTO.fromEntity(saved);
        if (Boolean.TRUE.equals(form.getMaterialize())) {
            dto.setMaterializeResult(materializeSkillBundleInternal(tenantId, saved));
        }
        return dto;
    }

    @Transactional
    public SkillBundleDTO syncMyAccountSkillBundle(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            SyncAccountSkillBundleForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(clientAppId, "clientAppId is required");
        Assert.hasText(upstreamUserId, "upstreamUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        userGrantService.checkUpstreamUserAccess(tenantId, clientAppId, upstreamUserId);

        SyncSkillBundleForm full = new SyncSkillBundleForm();
        full.setClientAppId(clientAppId);
        full.setScope(SCOPE_ACCOUNT_PRIVATE);
        full.setAccountId(upstreamUserId);
        full.setSkillId(form.getSkillId());
        full.setName(form.getName());
        full.setDescription(form.getDescription());
        full.setStatus(form.getStatus());
        full.setMarkdownBody(form.getMarkdownBody());
        full.setResources(form.getResources());
        full.setFunctions(form.getFunctions());
        full.setMaterialize(form.getMaterialize());
        return syncSkillBundle(tenantId, upstreamUserId, full);
    }

    @Transactional
    public SkillClearResultDTO clearPublicSkillBundles(String tenantId, String actorUserId, ClearSkillBundleForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getClientAppId(), "clientAppId is required");

        List<SkillBundleEntity> bundles = StringUtils.hasText(form.getSkillId())
                ? skillBundleRepository.findByTenantIdAndClientAppIdAndScopeAndSkillId(
                        tenantId, form.getClientAppId(), SCOPE_CLIENT_APP_PUBLIC, form.getSkillId())
                : skillBundleRepository.findByTenantIdAndClientAppIdAndScope(
                        tenantId, form.getClientAppId(), SCOPE_CLIENT_APP_PUBLIC);
        return clearSkillBundles(tenantId, form, SCOPE_CLIENT_APP_PUBLIC, bundles);
    }

    @Transactional
    public SkillClearResultDTO clearAccountSkillBundles(String tenantId, String actorUserId, ClearSkillBundleForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getClientAppId(), "clientAppId is required");
        Assert.hasText(form.getAccountId(), "accountId is required");

        List<SkillBundleEntity> bundles;
        if (StringUtils.hasText(form.getSkillId())) {
            bundles = skillBundleRepository
                    .findByTenantIdAndClientAppIdAndScopeAndAccountIdAndSkillId(
                            tenantId,
                            form.getClientAppId(),
                            SCOPE_ACCOUNT_PRIVATE,
                            form.getAccountId(),
                            form.getSkillId())
                    .map(List::of)
                    .orElseGet(List::of);
        } else {
            bundles = skillBundleRepository.findByTenantIdAndClientAppIdAndScopeAndAccountId(
                    tenantId, form.getClientAppId(), SCOPE_ACCOUNT_PRIVATE, form.getAccountId());
        }
        return clearSkillBundles(tenantId, form, SCOPE_ACCOUNT_PRIVATE, bundles);
    }

    private SkillClearResultDTO clearSkillBundles(
            String tenantId,
            ClearSkillBundleForm form,
            String scope,
            List<SkillBundleEntity> bundles) {
        List<SkillBundleEntity> matchedBundles = bundles == null ? List.of() : bundles;
        boolean dryRun = Boolean.TRUE.equals(form.getDryRun());
        Set<String> matchedSkillIds = new LinkedHashSet<>();
        matchedBundles.forEach(bundle -> matchedSkillIds.add(bundle.getSkillId()));

        Set<String> legacySkillIds = new LinkedHashSet<>();
        int grantCount = 0;
        int allowlistCount = 0;
        for (String skillId : matchedSkillIds) {
            if (shouldClearLegacySkill(tenantId, skillId, matchedBundles)) {
                legacySkillIds.add(skillId);
                grantCount += countList(grantRepository.findByTenantIdAndClientAppIdAndSkillId(
                        tenantId, form.getClientAppId(), skillId).map(List::of).orElseGet(List::of));
                allowlistCount += countList(allowlistRepository.findByTenantIdAndSkillId(tenantId, skillId));
            }
        }

        SkillClearResultDTO result = new SkillClearResultDTO();
        result.setScope(scope);
        result.setClientAppId(form.getClientAppId());
        result.setAccountId(form.getAccountId());
        result.setSkillId(form.getSkillId());
        result.setDryRun(dryRun);
        result.setExecuted(!dryRun);
        result.setSkillIds(List.copyOf(matchedSkillIds));
        result.setMatchedSkillCount(matchedSkillIds.size());
        result.setSkillBundleCount(matchedBundles.size());
        result.setLegacySkillCount(legacySkillIds.size());
        result.setClientAppSkillGrantCount(grantCount);
        result.setSkillFunctionAllowlistCount(allowlistCount);
        result.setMaterializedBundleCount(matchedBundles.size());
        result.setCacheCount(0);

        if (dryRun || matchedBundles.isEmpty()) {
            result.setWorkerClearStatus(dryRun ? "SKIPPED_DRY_RUN" : "ZERO_MATCH");
            return result;
        }

        skillBundleRepository.deleteAll(matchedBundles);
        for (String skillId : legacySkillIds) {
            grantRepository.findByTenantIdAndClientAppIdAndSkillId(tenantId, form.getClientAppId(), skillId)
                    .ifPresent(grantRepository::delete);
            List<SkillFunctionAllowlistEntity> allowlist = allowlistRepository.findByTenantIdAndSkillId(tenantId, skillId);
            if (allowlist != null && !allowlist.isEmpty()) {
                allowlistRepository.deleteAll(allowlist);
            }
            skillRepository.findByTenantIdAndSkillId(tenantId, skillId)
                    .ifPresent(skillRepository::delete);
        }

        WorkerClearOutcome workerOutcome = clearMaterializedWorkerSkills(scope, form);
        result.setWorkerClearStatus(workerOutcome.status());
        result.setWorkerStatusCode(workerOutcome.statusCode());
        result.setWorkerResponse(workerOutcome.response());
        return result;
    }

    private boolean shouldClearLegacySkill(String tenantId, String skillId, List<SkillBundleEntity> matchedBundles) {
        Set<String> matchedKeys = new LinkedHashSet<>();
        matchedBundles.stream()
                .filter(bundle -> skillId.equals(bundle.getSkillId()))
                .map(this::bundleKey)
                .forEach(matchedKeys::add);
        List<SkillBundleEntity> allForSkill = skillBundleRepository.findByTenantIdAndSkillId(tenantId, skillId);
        if (allForSkill == null || allForSkill.isEmpty()) {
            return true;
        }
        return allForSkill.stream().map(this::bundleKey).allMatch(matchedKeys::contains);
    }

    private String bundleKey(SkillBundleEntity bundle) {
        return bundle.getClientAppId() + "|" + bundle.getScope() + "|"
                + bundle.getAccountId() + "|" + bundle.getSkillId();
    }

    private int countList(List<?> values) {
        return values == null ? 0 : values.size();
    }

    private WorkerClearOutcome clearMaterializedWorkerSkills(String scope, ClearSkillBundleForm form) {
        if (!StringUtils.hasText(devSyncWorkerUrl)) {
            return new WorkerClearOutcome("SKIPPED", null, "dev-sync-worker-url is not configured");
        }
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("scope", SCOPE_ACCOUNT_PRIVATE.equals(scope) ? "account" : "public");
            payload.put("client_app_id", form.getClientAppId());
            payload.put("account_id", form.getAccountId());
            payload.put("skill_id", form.getSkillId());

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(devSyncWorkerUrl + "/api/v1/skills/clear"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (StringUtils.hasText(devSyncWorkerToken)) {
                requestBuilder.header("Authorization", "Bearer " + devSyncWorkerToken);
            }

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            String status = response.statusCode() >= 200 && response.statusCode() < 300 ? "CLEARED" : "FAILED";
            return new WorkerClearOutcome(status, response.statusCode(), response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new WorkerClearOutcome("FAILED", null, "Worker skill clear interrupted");
        } catch (Exception e) {
            return new WorkerClearOutcome("FAILED", null, e.getMessage());
        }
    }

    private record WorkerClearOutcome(String status, Integer statusCode, String response) {
    }

    @Transactional(readOnly = true)
    public Optional<SkillBundleEntity> findSkillBundle(
            String tenantId,
            String clientAppId,
            String scope,
            String accountId,
            String skillId) {
        return skillBundleRepository.findByTenantIdAndClientAppIdAndScopeAndAccountIdAndSkillId(
                tenantId,
                clientAppId,
                normalizeScope(scope),
                normalizeAccountId(normalizeScope(scope), accountId),
                skillId);
    }

    private void triggerGrantedPublicSkillMaterialize(String tenantId, SkillEntity skill, String reason) {
        if (skill == null || !STATUS_ENABLED.equals(skill.getStatus())) {
            return;
        }

        List<ClientAppSkillGrantEntity> grants = grantRepository.findByTenantIdAndSkillId(tenantId, skill.getSkillId());
        if (grants == null) {
            grants = List.of();
        }
        List<ClientAppSkillGrantEntity> enabledGrants = grants.stream()
                .filter(grant -> STATUS_ENABLED.equals(grant.getStatus()))
                .toList();
        if (enabledGrants.isEmpty()) {
            log.debug("Skip auto materialize public skill {} after {}: no enabled client app grant",
                    skill.getSkillId(), reason);
            return;
        }

        for (ClientAppSkillGrantEntity grant : enabledGrants) {
            triggerPublicSkillMaterializeForClientApp(tenantId, skill, grant.getClientAppId(), reason);
        }
    }

    private void triggerPublicSkillMaterializeForClientApp(String tenantId, SkillEntity skill, String clientAppId, String reason) {
        try {
            SkillMaterializeResultDTO result = materializePublicSkillInternal(tenantId, skill, clientAppId);
            log.info("Auto materialized public skill {} for clientApp {} after {}: status={}, workerStatusCode={}",
                    skill.getSkillId(), clientAppId, reason, result.getStatus(), result.getWorkerStatusCode());
        } catch (Exception e) {
            log.warn("Auto materialize public skill {} for clientApp {} after {} failed: {}",
                    skill.getSkillId(), clientAppId, reason, e.getMessage());
        }
    }

    private SkillMaterializeResultDTO materializePublicSkillInternal(String tenantId, SkillEntity skill, String clientAppId) {
        if (!STATUS_ENABLED.equals(skill.getStatus())) {
            throw new IllegalStateException("Skill is disabled");
        }

        SkillMaterializeResultDTO result = new SkillMaterializeResultDTO();
        result.setSkillId(skill.getSkillId());
        result.setScope("public");
        result.setClientAppId(clientAppId);
        result.setWorkerUrl(devSyncWorkerUrl);

        if (!StringUtils.hasText(devSyncWorkerUrl)) {
            result.setStatus("SKIPPED");
            result.setWorkerResponse("dev-sync-worker-url is not configured");
            return result;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("skill_id", skill.getSkillId());
            payload.put("scope", "public");
            payload.put("name", skill.getSkillId());
            payload.put("display_name", StringUtils.hasText(skill.getName()) ? skill.getName() : skill.getSkillId());
            payload.put("description", skill.getDescription());
            payload.put("markdown_body", buildMaterializedMarkdown(tenantId, skill));
            payload.put("resources", parseResourcesJson(skill.getResourcesJson()));
            if (StringUtils.hasText(clientAppId)) {
                payload.put("client_app_id", clientAppId);
            }

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(devSyncWorkerUrl + "/api/v1/skills/materialize"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (StringUtils.hasText(devSyncWorkerToken)) {
                requestBuilder.header("Authorization", "Bearer " + devSyncWorkerToken);
            }

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            result.setWorkerStatusCode(response.statusCode());
            result.setWorkerResponse(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.setStatus("FAILED");
                throw new IllegalStateException("Worker skill materialize failed: HTTP " + response.statusCode() + " " + response.body());
            }

            result.setStatus("MATERIALIZED");
            log.info("Materialized public skill {} to worker {}", skill.getSkillId(), devSyncWorkerUrl);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker skill materialize interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Worker skill materialize failed", e);
        }
    }

    private SkillMaterializeResultDTO materializeSkillBundleInternal(String tenantId, SkillBundleEntity bundle) {
        if (!STATUS_ENABLED.equals(bundle.getStatus())) {
            throw new IllegalStateException("Skill bundle is disabled");
        }

        boolean accountScope = SCOPE_ACCOUNT_PRIVATE.equals(bundle.getScope());
        SkillMaterializeResultDTO result = new SkillMaterializeResultDTO();
        result.setSkillId(bundle.getSkillId());
        result.setScope(accountScope ? "account" : "public");
        result.setClientAppId(bundle.getClientAppId());
        result.setAccountId(accountScope ? bundle.getAccountId() : null);
        result.setWorkerUrl(devSyncWorkerUrl);

        if (!StringUtils.hasText(devSyncWorkerUrl)) {
            result.setStatus("SKIPPED");
            result.setWorkerResponse("dev-sync-worker-url is not configured");
            return result;
        }

        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("skill_id", bundle.getSkillId());
            payload.put("scope", accountScope ? "account" : "public");
            payload.put("name", bundle.getSkillId());
            payload.put("display_name", StringUtils.hasText(bundle.getName()) ? bundle.getName() : bundle.getSkillId());
            payload.put("description", bundle.getDescription());
            payload.put("markdown_body", buildMaterializedMarkdown(tenantId, bundle));
            payload.put("resources", parseResourcesJson(bundle.getResourcesJson()));
            payload.put("client_app_id", bundle.getClientAppId());
            if (accountScope) {
                payload.put("account_id", bundle.getAccountId());
            }

            String json = objectMapper.writeValueAsString(payload);
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(devSyncWorkerUrl + "/api/v1/skills/materialize"))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json));

            if (StringUtils.hasText(devSyncWorkerToken)) {
                requestBuilder.header("Authorization", "Bearer " + devSyncWorkerToken);
            }

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());

            result.setWorkerStatusCode(response.statusCode());
            result.setWorkerResponse(response.body());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                result.setStatus("FAILED");
                throw new IllegalStateException("Worker skill materialize failed: HTTP " + response.statusCode() + " " + response.body());
            }

            result.setStatus("MATERIALIZED");
            log.info("Materialized skill bundle {} scope={} to worker {}", bundle.getSkillId(), bundle.getScope(), devSyncWorkerUrl);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker skill materialize interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Worker skill materialize failed", e);
        }
    }

    private String buildMaterializedMarkdown(String tenantId, SkillEntity skill) {
        StringBuilder md = new StringBuilder();
        if (StringUtils.hasText(skill.getMarkdownBody())) {
            md.append(skill.getMarkdownBody().trim()).append("\n");
        }

        List<SkillFunctionAllowlistEntity> allowlist = allowlistRepository.findByTenantIdAndSkillId(tenantId, skill.getSkillId());
        if (allowlist == null) {
            allowlist = List.of();
        }
        List<SkillFunctionAllowlistEntity> enabledAllowlist = allowlist.stream()
                .filter(item -> STATUS_ENABLED.equals(item.getStatus()))
                .toList();
        if (enabledAllowlist.isEmpty()) {
            return md.toString();
        }

        if (md.length() > 0) {
            md.append("\n");
        }
        md.append("## Allowed Business Functions\n");
        for (SkillFunctionAllowlistEntity item : enabledAllowlist) {
            functionRepository.findByTenantIdAndFunctionId(tenantId, item.getFunctionId())
                    .filter(function -> STATUS_ENABLED.equals(function.getStatus()))
                    .ifPresent(function -> appendFunctionSummary(md, tenantId, function));
        }
        return md.toString();
    }

    private String buildMaterializedMarkdown(String tenantId, SkillBundleEntity bundle) {
        StringBuilder md = new StringBuilder();
        if (StringUtils.hasText(bundle.getMarkdownBody())) {
            md.append(bundle.getMarkdownBody().trim()).append("\n");
        }

        List<SkillBundleFunctionForm> functions = parseFunctionsJson(bundle.getFunctionsJson());
        List<SkillBundleFunctionForm> enabledFunctions = functions.stream()
                .filter(item -> item != null && STATUS_ENABLED.equals(normalizeStatus(item.getStatus()))
                        && StringUtils.hasText(item.getFunctionId()))
                .toList();
        if (enabledFunctions.isEmpty()) {
            return md.toString();
        }

        if (md.length() > 0) {
            md.append("\n");
        }
        md.append("## Allowed Business Functions\n");
        for (SkillBundleFunctionForm item : enabledFunctions) {
            functionRepository.findByTenantIdAndFunctionId(tenantId, item.getFunctionId())
                    .filter(function -> STATUS_ENABLED.equals(function.getStatus()))
                    .ifPresent(function -> appendFunctionSummary(md, tenantId, function));
        }
        return md.toString();
    }

    private void syncLegacySkillIndex(SkillBundleEntity bundle, String actorUserId) {
        SkillEntity skill = skillRepository.findByTenantIdAndSkillId(bundle.getTenantId(), bundle.getSkillId())
                .orElseGet(SkillEntity::new);
        skill.setTenantId(bundle.getTenantId());
        skill.setSkillId(bundle.getSkillId());
        skill.setName(bundle.getName());
        skill.setDescription(bundle.getDescription());
        skill.setMarkdownBody(bundle.getMarkdownBody());
        skill.setResourcesJson(bundle.getResourcesJson());
        skill.setStatus(bundle.getStatus());
        if (!StringUtils.hasText(skill.getCreatedBy())) {
            skill.setCreatedBy(actorUserId);
        }
        skillRepository.save(skill);
    }

    private void syncLegacySkillGrant(SkillBundleEntity bundle, String actorUserId) {
        ClientAppSkillGrantEntity grant = grantRepository
                .findByTenantIdAndClientAppIdAndSkillId(bundle.getTenantId(), bundle.getClientAppId(), bundle.getSkillId())
                .orElseGet(() -> {
                    ClientAppSkillGrantEntity entity = new ClientAppSkillGrantEntity();
                    entity.setGrantId(UUID.randomUUID().toString().replace("-", ""));
                    return entity;
                });
        grant.setTenantId(bundle.getTenantId());
        grant.setClientAppId(bundle.getClientAppId());
        grant.setSkillId(bundle.getSkillId());
        grant.setStatus(bundle.getStatus());
        if (!StringUtils.hasText(grant.getCreatedBy())) {
            grant.setCreatedBy(actorUserId);
        }
        grantRepository.save(grant);
    }

    private void syncLegacyFunctionAllowlist(
            SkillBundleEntity bundle,
            String actorUserId,
            List<SkillBundleFunctionForm> functions) {
        if (functions == null || functions.isEmpty()) {
            return;
        }
        for (SkillBundleFunctionForm function : functions) {
            if (function == null || !StringUtils.hasText(function.getFunctionId())) {
                continue;
            }
            SkillFunctionAllowlistEntity allowlist = allowlistRepository
                    .findByTenantIdAndSkillIdAndFunctionId(bundle.getTenantId(), bundle.getSkillId(), function.getFunctionId())
                    .orElseGet(() -> {
                        SkillFunctionAllowlistEntity entity = new SkillFunctionAllowlistEntity();
                        entity.setAllowlistId(UUID.randomUUID().toString().replace("-", ""));
                        return entity;
                    });
            allowlist.setTenantId(bundle.getTenantId());
            allowlist.setSkillId(bundle.getSkillId());
            allowlist.setFunctionId(function.getFunctionId());
            allowlist.setStatus(normalizeStatus(function.getStatus()));
            if (!StringUtils.hasText(allowlist.getCreatedBy())) {
                allowlist.setCreatedBy(actorUserId);
            }
            allowlistRepository.save(allowlist);
        }
    }

    private void validateBundleFunctions(String tenantId, String clientAppId, List<SkillBundleFunctionForm> functions) {
        if (functions == null || functions.isEmpty()) {
            return;
        }
        for (SkillBundleFunctionForm function : functions) {
            if (function == null) {
                throw new IllegalArgumentException("skill bundle function is required");
            }
            Assert.hasText(function.getFunctionId(), "functionId is required");
            normalizeStatus(function.getStatus());

            BusinessFunctionEntity functionEntity = functionRepository
                    .findByTenantIdAndFunctionId(tenantId, function.getFunctionId())
                    .orElseThrow(() -> new IllegalArgumentException("Function not found: " + function.getFunctionId()));
            if (!STATUS_ENABLED.equals(functionEntity.getStatus())) {
                throw new IllegalStateException("Function is disabled");
            }
            if (!hasEnabledClientAppFunctionGrant(tenantId, clientAppId, function.getFunctionId(), function.getVersion())) {
                throw new IllegalStateException("Client App is not granted access to function: " + function.getFunctionId());
            }
        }
    }

    private boolean hasEnabledClientAppFunctionGrant(String tenantId, String clientAppId, String functionId, String version) {
        if (StringUtils.hasText(version)) {
            return functionGrantRepository
                    .findByTenantIdAndClientAppIdAndFunctionIdAndVersion(tenantId, clientAppId, functionId, version)
                    .filter(grant -> STATUS_ENABLED.equals(grant.getStatus()))
                    .isPresent();
        }
        List<ClientAppFunctionGrantEntity> grants = functionGrantRepository.findByTenantIdAndClientAppId(tenantId, clientAppId);
        return grants != null && grants.stream()
                .anyMatch(grant -> functionId.equals(grant.getFunctionId()) && STATUS_ENABLED.equals(grant.getStatus()));
    }

    private String serializeFunctions(List<SkillBundleFunctionForm> functions) {
        if (functions == null || functions.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(functions);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid skill bundle functions", e);
        }
    }

    private List<SkillBundleFunctionForm> parseFunctionsJson(String functionsJson) {
        if (!StringUtils.hasText(functionsJson)) {
            return List.of();
        }
        try {
            List<SkillBundleFunctionForm> functions = objectMapper.readValue(
                    functionsJson, new TypeReference<List<SkillBundleFunctionForm>>() {});
            return functions == null ? List.of() : functions;
        } catch (Exception e) {
            throw new IllegalStateException("Stored skill bundle functionsJson is invalid", e);
        }
    }

    private String normalizeScope(String scope) {
        String value = StringUtils.hasText(scope)
                ? scope.trim().replace('-', '_').toUpperCase()
                : SCOPE_CLIENT_APP_PUBLIC;
        if ("CLIENT_APP_PUBLIC".equals(value) || "PUBLIC".equals(value)) {
            return SCOPE_CLIENT_APP_PUBLIC;
        }
        if ("ACCOUNT_PRIVATE".equals(value) || "ACCOUNT".equals(value) || "PRIVATE".equals(value)) {
            return SCOPE_ACCOUNT_PRIVATE;
        }
        throw new IllegalArgumentException("invalid scope");
    }

    private String normalizeAccountId(String scope, String accountId) {
        if (SCOPE_CLIENT_APP_PUBLIC.equals(scope)) {
            return "";
        }
        Assert.hasText(accountId, "accountId is required");
        return accountId.trim();
    }

    private String normalizeStatus(String status) {
        String value = StringUtils.hasText(status) ? status : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(value) && !STATUS_DISABLED.equals(value)) {
            throw new IllegalArgumentException("invalid status");
        }
        return value;
    }

    private String serializeResources(List<SkillResourceForm> resources) {
        if (resources == null || resources.isEmpty()) {
            return null;
        }
        if (resources.size() > MAX_SKILL_RESOURCES) {
            throw new IllegalArgumentException("too many skill resources");
        }
        for (SkillResourceForm resource : resources) {
            validateResource(resource);
        }
        try {
            return objectMapper.writeValueAsString(resources);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid skill resources", e);
        }
    }

    private List<Map<String, Object>> parseResourcesJson(String resourcesJson) {
        if (!StringUtils.hasText(resourcesJson)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(resourcesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Stored skill resourcesJson is invalid", e);
        }
    }

    private void validateResource(SkillResourceForm resource) {
        if (resource == null) {
            throw new IllegalArgumentException("skill resource is required");
        }
        validateResourcePath(resource.getPath());
        String content = resource.getContent();
        if (content == null) {
            throw new IllegalArgumentException("skill resource content is required: " + resource.getPath());
        }
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_SKILL_RESOURCE_BYTES) {
            throw new IllegalArgumentException("skill resource is too large: " + resource.getPath());
        }
    }

    private void validateResourcePath(String path) {
        Assert.hasText(path, "skill resource path is required");
        String normalized = path.replace('\\', '/');
        if (!normalized.equals(path) || normalized.startsWith("/") || normalized.contains("//")
                || normalized.contains("/./") || normalized.contains("/../")
                || normalized.startsWith("./") || normalized.startsWith("../")
                || normalized.endsWith("/")) {
            throw new IllegalArgumentException("invalid skill resource path: " + path);
        }
        if (!(normalized.startsWith("references/") || normalized.startsWith("assets/"))) {
            throw new IllegalArgumentException("skill resource path must start with references/ or assets/: " + path);
        }
        String[] segments = normalized.split("/");
        for (String segment : segments) {
            if (!SAFE_RESOURCE_SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("invalid skill resource path segment: " + segment);
            }
        }
    }

    private void appendFunctionSummary(StringBuilder md, String tenantId, BusinessFunctionEntity function) {
        md.append("- ").append(function.getFunctionId());
        if (StringUtils.hasText(function.getCurrentVersion())) {
            md.append("@").append(function.getCurrentVersion());
        }
        if (StringUtils.hasText(function.getName())) {
            md.append(": ").append(function.getName());
        }
        if (StringUtils.hasText(function.getDescription())) {
            md.append(" - ").append(function.getDescription());
        }
        if (StringUtils.hasText(function.getCurrentVersion())) {
            versionRepository.findByTenantIdAndFunctionIdAndVersion(
                            tenantId, function.getFunctionId(), function.getCurrentVersion())
                    .filter(version -> STATUS_ENABLED.equals(version.getStatus()))
                    .map(BusinessFunctionVersionEntity::getLlmVisibleSummary)
                    .filter(StringUtils::hasText)
                    .ifPresent(summary -> md.append(" ").append(summary.replace("\r", " ").replace("\n", " ")));
        }
        md.append("\n");
    }

    @Transactional(readOnly = true)
    public void checkClientAppSkillAccess(String tenantId, String clientAppId, String skillId) {
        clientAppService.requireActiveClientApp(tenantId, clientAppId);

        SkillEntity skill = skillRepository.findByTenantIdAndSkillId(tenantId, skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        if (!STATUS_ENABLED.equals(skill.getStatus())) {
            throw new IllegalStateException("Skill is disabled");
        }

        ClientAppSkillGrantEntity grant = grantRepository.findByTenantIdAndClientAppIdAndSkillId(tenantId, clientAppId, skillId)
                .orElseThrow(() -> new IllegalStateException("Client App is not granted access to this skill"));
        if (!STATUS_ENABLED.equals(grant.getStatus())) {
            throw new IllegalStateException("Client App skill grant is disabled");
        }
    }

    @Transactional(readOnly = true)
    public void checkSkillFunctionAccess(String tenantId, String skillId, String functionId) {
        SkillEntity skill = skillRepository.findByTenantIdAndSkillId(tenantId, skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
        if (!STATUS_ENABLED.equals(skill.getStatus())) {
            throw new IllegalStateException("Skill is disabled");
        }

        SkillFunctionAllowlistEntity allowlist = allowlistRepository.findByTenantIdAndSkillIdAndFunctionId(tenantId, skillId, functionId)
                .orElseThrow(() -> new IllegalStateException("Function is not allowlisted for this skill"));
        if (!STATUS_ENABLED.equals(allowlist.getStatus())) {
            throw new IllegalStateException("Skill function allowlist is disabled");
        }
    }

    @Transactional(readOnly = true)
    public SkillEntity getSkill(String tenantId, String skillId) {
        return skillRepository.findByTenantIdAndSkillId(tenantId, skillId)
                .orElseThrow(() -> new IllegalArgumentException("Skill not found: " + skillId));
    }
}
