package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppSkillGrantDTO;
import com.foggy.navigator.business.agent.model.dto.SkillDTO;
import com.foggy.navigator.business.agent.model.dto.SkillFunctionAllowlistDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppSkillGrantEntity;
import com.foggy.navigator.business.agent.model.entity.SkillEntity;
import com.foggy.navigator.business.agent.model.entity.SkillFunctionAllowlistEntity;
import com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm;
import com.foggy.navigator.business.agent.model.form.CreateSkillForm;
import com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppSkillGrantRepository;
import com.foggy.navigator.business.agent.repository.SkillFunctionAllowlistRepository;
import com.foggy.navigator.business.agent.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
    private final ClientAppService clientAppService;

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

        return SkillDTO.fromEntity(skillRepository.save(skill));
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

        return SkillFunctionAllowlistDTO.fromEntity(allowlistRepository.save(allowlist));
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

        return ClientAppSkillGrantDTO.fromEntity(grantRepository.save(grant));
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
