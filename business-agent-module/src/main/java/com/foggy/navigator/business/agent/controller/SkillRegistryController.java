package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.ClientAppSkillGrantDTO;
import com.foggy.navigator.business.agent.model.dto.SkillBundleDTO;
import com.foggy.navigator.business.agent.model.dto.SkillDTO;
import com.foggy.navigator.business.agent.model.dto.SkillFunctionAllowlistDTO;
import com.foggy.navigator.business.agent.model.dto.SkillMaterializeResultDTO;
import com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm;
import com.foggy.navigator.business.agent.model.form.CreateSkillForm;
import com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm;
import com.foggy.navigator.business.agent.model.form.SyncSkillBundleForm;
import com.foggy.navigator.business.agent.service.SkillRegistryService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class SkillRegistryController {

    private final SkillRegistryService skillRegistryService;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/skills")
    public SkillDTO createSkill(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @RequestBody CreateSkillForm form) {
        return skillRegistryService.createSkill(tenantId, actorUserId, form);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/skills/{skillId}/functions")
    public SkillFunctionAllowlistDTO addFunctionToSkillAllowlist(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @PathVariable String skillId,
            @RequestBody AddFunctionToSkillForm form) {
        return skillRegistryService.addFunctionToSkillAllowlist(tenantId, skillId, actorUserId, form);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/client-apps/{clientAppId}/skill-grants")
    public ClientAppSkillGrantDTO grantSkillToClientApp(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @PathVariable String clientAppId,
            @RequestBody GrantSkillToClientAppForm form) {
        return skillRegistryService.grantSkillToClientApp(tenantId, clientAppId, actorUserId, form);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/skills/{skillId}/materialize")
    public SkillMaterializeResultDTO materializePublicSkill(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable String skillId) {
        return skillRegistryService.materializePublicSkill(tenantId, skillId);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/skill-bundles/sync")
    public SkillBundleDTO syncSkillBundle(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String actorUserId,
            @RequestBody SyncSkillBundleForm form) {
        return skillRegistryService.syncSkillBundle(tenantId, actorUserId, form);
    }
}
