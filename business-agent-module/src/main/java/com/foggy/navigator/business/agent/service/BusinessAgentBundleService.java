package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.BusinessAgentBundleDTO;
import com.foggy.navigator.business.agent.model.dto.SkillBundleDTO;
import com.foggy.navigator.business.agent.model.form.SyncBusinessAgentBundleForm;
import com.foggy.navigator.business.agent.model.form.SyncSkillBundleForm;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class BusinessAgentBundleService {

    public static final String AGENT_TYPE_LANGGRAPH = "LOCAL_LANGGRAPH_WORKER";

    private final BusinessCodingAgentRepository agentRepository;
    private final SkillRegistryService skillRegistryService;
    private final ClientAppService clientAppService;
    private final ClientAppModelConfigGrantService modelConfigGrantService;
    private final ObjectMapper objectMapper;

    @Transactional
    public BusinessAgentBundleDTO syncAgentBundle(String tenantId, String actorUserId, SyncBusinessAgentBundleForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }

        Assert.hasText(form.getClientAppId(), "clientAppId is required");
        String agentId = resolveAgentId(form);
        String skillId = StringUtils.hasText(form.getSkillId()) ? form.getSkillId().trim() : agentId;
        Assert.hasText(form.getName(), "name is required");
        Assert.hasText(form.getWorkerId(), "workerId is required");

        clientAppService.requireActiveClientApp(tenantId, form.getClientAppId());
        String defaultModelConfigId = normalizeDefaultModelConfigId(tenantId, form.getClientAppId(), form.getDefaultModelConfigId());

        CodingAgentEntity entity = agentRepository.findByAgentIdAndTenantId(agentId, tenantId)
                .orElseGet(CodingAgentEntity::new);

        entity.setAgentId(agentId);
        entity.setUserId(actorUserId);
        entity.setTenantId(tenantId);
        entity.setName(form.getName().trim());
        entity.setDescription(blankToNull(form.getDescription()));
        entity.setAgentType(AGENT_TYPE_LANGGRAPH);
        entity.setWorkerId(form.getWorkerId().trim());
        entity.setDefaultModelConfigId(defaultModelConfigId);
        entity.setDefaultModel(blankToNull(form.getDefaultModel()));
        entity.setSkills(buildSkillSummary(skillId, form.getName(), form.getDescription()));
        CodingAgentEntity saved = agentRepository.save(entity);

        SkillBundleDTO skillBundle = syncPublicSkillBundle(tenantId, actorUserId, form, skillId);
        return BusinessAgentBundleDTO.fromEntity(saved, form.getClientAppId(), skillId, skillBundle);
    }

    private SkillBundleDTO syncPublicSkillBundle(
            String tenantId,
            String actorUserId,
            SyncBusinessAgentBundleForm form,
            String skillId) {
        SyncSkillBundleForm skillForm = new SyncSkillBundleForm();
        skillForm.setClientAppId(form.getClientAppId());
        skillForm.setScope(SkillRegistryService.SCOPE_CLIENT_APP_PUBLIC);
        skillForm.setSkillId(skillId);
        skillForm.setName(form.getName());
        skillForm.setDescription(form.getDescription());
        skillForm.setStatus(form.getStatus());
        skillForm.setMarkdownBody(form.getMarkdownBody());
        skillForm.setResources(form.getResources());
        skillForm.setFunctions(form.getFunctions());
        skillForm.setMaterialize(form.getMaterialize());
        return skillRegistryService.syncSkillBundle(tenantId, actorUserId, skillForm);
    }

    private String resolveAgentId(SyncBusinessAgentBundleForm form) {
        String agentId = StringUtils.hasText(form.getAgentId()) ? form.getAgentId() : form.getAgentCode();
        Assert.hasText(agentId, "agentId is required");
        return agentId.trim();
    }

    private String normalizeDefaultModelConfigId(String tenantId, String clientAppId, String defaultModelConfigId) {
        if (StringUtils.hasText(defaultModelConfigId)) {
            return modelConfigGrantService.resolveEffectiveModelConfigId(tenantId, clientAppId, defaultModelConfigId);
        }
        return modelConfigGrantService.resolveEffectiveModelConfigId(tenantId, clientAppId, null);
    }

    private String buildSkillSummary(String skillId, String name, String description) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of(
                    "id", skillId,
                    "name", name,
                    "description", description == null ? "" : description
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid agent skill summary", e);
        }
    }

    private String blankToNull(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }
}
