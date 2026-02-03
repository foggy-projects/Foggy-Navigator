package com.foggy.navigator.metadata.query.config.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import com.foggy.navigator.common.form.SkillConfigForm;
import com.foggy.navigator.spi.config.SkillConfigManager;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Skill 配置管理 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/config/skills")
@RequiredArgsConstructor
@RequireAuth
public class SkillConfigController {

    private final SkillConfigManager skillConfigManager;

    /**
     * 保存 Skill 配置
     */
    @PostMapping
    public RX<String> saveSkillConfig(@RequestBody SkillConfigForm form) {
        CurrentUser user = UserContext.getCurrentUser();
        String name = form.getBasicInfo() != null ? form.getBasicInfo().getName() : "unknown";
        log.info("Save skill config: name={}, operator={}", name, user.getUsername());

        // 自动填充租户ID
        if (form.getTenantId() == null && !user.isSuperAdmin()) {
            form.setTenantId(user.getTenantId());
        }

        String id = skillConfigManager.saveSkillConfig(form);
        return RX.ok(id);
    }

    /**
     * 更新 Skill 配置
     */
    @PutMapping("/{skillId}")
    public RX<Void> updateSkillConfig(
            @PathVariable String skillId,
            @RequestBody SkillConfigForm form) {
        log.info("Update skill config: id={}", skillId);
        skillConfigManager.updateSkillConfig(skillId, form);
        return RX.ok();
    }

    /**
     * 更新 Skill 状态
     */
    @PatchMapping("/{skillId}/status")
    public RX<Void> updateSkillStatus(
            @PathVariable String skillId,
            @RequestParam SkillStatus status) {
        log.info("Update skill status: id={}, status={}", skillId, status);
        skillConfigManager.updateSkillStatus(skillId, status);
        return RX.ok();
    }

    /**
     * 删除 Skill 配置
     */
    @DeleteMapping("/{skillId}")
    public RX<Void> deleteSkillConfig(@PathVariable String skillId) {
        log.info("Delete skill config: id={}", skillId);
        skillConfigManager.deleteSkillConfig(skillId);
        return RX.ok();
    }

    /**
     * 获取 Agent 可用的所有 Skill
     */
    @GetMapping
    public RX<List<SkillConfigDTO>> getSkillsForAgent(
            @RequestParam String agentId,
            @RequestParam(required = false) String tenantId) {
        CurrentUser user = UserContext.getCurrentUser();
        // 非超级管理员使用自己的租户ID
        if (tenantId == null && !user.isSuperAdmin()) {
            tenantId = user.getTenantId();
        }
        log.info("Get skills for agent: agentId={}, tenantId={}", agentId, tenantId);
        List<SkillConfigDTO> skills = skillConfigManager.getSkillsForAgent(agentId, tenantId);
        return RX.ok(skills);
    }

    /**
     * 按作用域获取 Skill 列表
     */
    @GetMapping("/by-scope")
    public RX<List<SkillConfigDTO>> getSkillsByScope(
            @RequestParam SkillScope scope,
            @RequestParam(required = false) String tenantId) {
        CurrentUser user = UserContext.getCurrentUser();
        if (tenantId == null && !user.isSuperAdmin()) {
            tenantId = user.getTenantId();
        }
        log.info("Get skills by scope: scope={}, tenantId={}", scope, tenantId);
        List<SkillConfigDTO> skills = skillConfigManager.getSkillsByScope(scope, tenantId);
        return RX.ok(skills);
    }

    /**
     * 获取单个 Skill 配置
     */
    @GetMapping("/{skillId}")
    public RX<SkillConfigDTO> getSkillConfig(@PathVariable String skillId) {
        log.info("Get skill config: id={}", skillId);
        SkillConfigDTO skill = skillConfigManager.getSkillConfig(skillId)
            .orElseThrow(() -> RX.throwB("Skill config not found: " + skillId));
        return RX.ok(skill);
    }
}
