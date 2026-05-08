package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppSkillGrantDTO;
import com.foggy.navigator.business.agent.model.dto.SkillDTO;
import com.foggy.navigator.business.agent.model.dto.SkillFunctionAllowlistDTO;
import com.foggy.navigator.business.agent.model.dto.SkillMaterializeResultDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm;
import com.foggy.navigator.business.agent.model.form.CreateSkillForm;
import com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.BusinessFunctionVersionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppSkillGrantRepository;
import com.foggy.navigator.business.agent.repository.SkillFunctionAllowlistRepository;
import com.foggy.navigator.business.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService.STATUS_DISABLED;
import static com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService.STATUS_ENABLED;
import org.springframework.util.Assert;

@Slf4j
@Service
@RequiredArgsConstructor
public class SkillRegistryService {

    private final SkillRepository skillRepository;
    private final SkillFunctionAllowlistRepository allowlistRepository;
    private final ClientAppSkillGrantRepository grantRepository;
    private final BusinessFunctionRepository functionRepository;
    private final BusinessFunctionVersionRepository versionRepository;
    private final ClientAppService clientAppService;
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
