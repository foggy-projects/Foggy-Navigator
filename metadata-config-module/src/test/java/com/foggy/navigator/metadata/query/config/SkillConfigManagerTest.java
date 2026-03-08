package com.foggy.navigator.metadata.query.config;

import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.common.entity.SkillConfigEntity;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import com.foggy.navigator.common.form.SkillBasicInfo;
import com.foggy.navigator.common.form.SkillConfigForm;
import com.foggy.navigator.common.form.SkillExecutionConfig;
import com.foggy.navigator.common.form.SkillTriggerConfig;
import com.foggy.navigator.metadata.query.config.repository.SkillConfigRepository;
import com.foggy.navigator.spi.config.SkillConfigManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SkillConfigManager 集成测试
 */
@SpringBootTest(classes = TestConfig.class)
@ActiveProfiles("test")
@Transactional
class SkillConfigManagerTest {

    @Autowired
    private SkillConfigManager skillConfigManager;

    @Autowired
    private SkillConfigRepository skillConfigRepo;

    @Test
    void testSaveSkillConfig_BasicInfo() {
        // 准备测试数据
        SkillConfigForm form = createTestSkillForm("test-skill", SkillScope.GLOBAL, null);

        // 执行保存
        String id = skillConfigManager.saveSkillConfig(form);

        // 验证结果
        assertNotNull(id);
        Optional<SkillConfigEntity> saved = skillConfigRepo.findById(id);
        assertTrue(saved.isPresent());
        assertEquals("test-skill", saved.get().getName());
        assertEquals(SkillScope.GLOBAL, saved.get().getScope());
        assertEquals(SkillStatus.ENABLED, saved.get().getStatus());
        assertEquals(50, saved.get().getPriority());
    }

    @Test
    void testSaveSkillConfig_WithTriggerConfig() {
        // 准备测试数据
        SkillConfigForm form = new SkillConfigForm();
        form.setTenantId("tenant-001");

        SkillBasicInfo basicInfo = new SkillBasicInfo();
        basicInfo.setName("trigger-test-skill");
        basicInfo.setScope(SkillScope.AGENT);
        basicInfo.setAgentId("test-agent");
        basicInfo.setStatus(SkillStatus.ENABLED);
        form.setBasicInfo(basicInfo);

        SkillTriggerConfig triggerConfig = new SkillTriggerConfig();
        triggerConfig.setKeywords(Arrays.asList("关键词1", "关键词2"));
        triggerConfig.setIntents(Arrays.asList("intent_a", "intent_b"));
        triggerConfig.setDelegationCondition("需要代码审查时分派");
        form.setTriggerConfig(triggerConfig);

        // 执行保存
        String id = skillConfigManager.saveSkillConfig(form);

        // 验证结果
        Optional<SkillConfigEntity> saved = skillConfigRepo.findById(id);
        assertTrue(saved.isPresent());
        assertNotNull(saved.get().getTriggerKeywords());
        assertTrue(saved.get().getTriggerKeywords().contains("关键词1"));
        assertEquals("需要代码审查时分派", saved.get().getDelegationCondition());
    }

    @Test
    void testSaveSkillConfig_WithExecutionConfig() {
        // 准备测试数据
        SkillConfigForm form = new SkillConfigForm();

        SkillBasicInfo basicInfo = new SkillBasicInfo();
        basicInfo.setName("execution-test-skill");
        basicInfo.setScope(SkillScope.SYSTEM);
        basicInfo.setStatus(SkillStatus.ENABLED);
        form.setBasicInfo(basicInfo);

        SkillExecutionConfig execConfig = new SkillExecutionConfig();
        execConfig.setExecutionLogic("1. 分析代码\n2. 生成报告");
        execConfig.setOutputFormat("Markdown 格式");
        execConfig.setMarkdownContent("# Skill 内容\n详细说明...");
        form.setExecutionConfig(execConfig);

        // 执行保存
        String id = skillConfigManager.saveSkillConfig(form);

        // 验证结果
        Optional<SkillConfigEntity> saved = skillConfigRepo.findById(id);
        assertTrue(saved.isPresent());
        assertEquals("1. 分析代码\n2. 生成报告", saved.get().getExecutionLogic());
        assertEquals("Markdown 格式", saved.get().getOutputFormat());
        assertTrue(saved.get().getMarkdownContent().contains("Skill 内容"));
    }

    @Test
    void testUpdateSkillConfig() {
        // 先保存一个配置
        String id = createTestSkill("update-test", SkillScope.GLOBAL, null);

        // 准备更新数据
        SkillConfigForm updateForm = new SkillConfigForm();
        SkillBasicInfo basicInfo = new SkillBasicInfo();
        basicInfo.setName("updated-skill");
        basicInfo.setStatus(SkillStatus.DISABLED);
        basicInfo.setPriority(10);
        updateForm.setBasicInfo(basicInfo);

        // 执行更新
        skillConfigManager.updateSkillConfig(id, updateForm);

        // 验证结果
        Optional<SkillConfigEntity> updated = skillConfigRepo.findById(id);
        assertTrue(updated.isPresent());
        assertEquals("updated-skill", updated.get().getName());
        assertEquals(SkillStatus.DISABLED, updated.get().getStatus());
        assertEquals(10, updated.get().getPriority());
        // 验证未更新的字段保持不变
        assertEquals(SkillScope.GLOBAL, updated.get().getScope());
    }

    @Test
    void testUpdateSkillStatus() {
        String id = createTestSkill("status-test", SkillScope.GLOBAL, null);

        skillConfigManager.updateSkillStatus(id, SkillStatus.DISABLED);

        Optional<SkillConfigEntity> entity = skillConfigRepo.findById(id);
        assertTrue(entity.isPresent());
        assertEquals(SkillStatus.DISABLED, entity.get().getStatus());
    }

    @Test
    void testDeleteSkillConfig() {
        String id = createTestSkill("delete-test", SkillScope.GLOBAL, null);

        skillConfigManager.deleteSkillConfig(id);

        assertFalse(skillConfigRepo.existsById(id));
    }

    @Test
    void testGetSkillConfig() {
        String id = createTestSkill("get-test", SkillScope.GLOBAL, null);

        Optional<SkillConfigDTO> dto = skillConfigManager.getSkillConfig(id);

        assertTrue(dto.isPresent());
        assertEquals(id, dto.get().getId());
        assertEquals("get-test", dto.get().getName());
    }

    @Test
    void testGetSkillConfig_NotFound() {
        Optional<SkillConfigDTO> dto = skillConfigManager.getSkillConfig("non-existent-id");

        assertFalse(dto.isPresent());
    }

    @Test
    void testGetSkillsForAgent() {
        // 创建不同作用域的 Skills
        createTestSkill("system-skill", SkillScope.SYSTEM, null);
        createTestSkill("global-skill", SkillScope.GLOBAL, null);
        createTestSkill("agent-skill", SkillScope.AGENT, "test-agent");
        createTestSkill("other-agent-skill", SkillScope.AGENT, "other-agent");
        createTestSkillWithTenant("tenant-skill", SkillScope.TENANT, "tenant-001");

        // 查询 test-agent 可用的 Skills
        List<SkillConfigDTO> skills = skillConfigManager.getSkillsForAgent("test-agent", "tenant-001");

        // 应该包含 SYSTEM, GLOBAL, 匹配的 AGENT, 匹配的 TENANT
        assertTrue(skills.size() >= 4);
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("system-skill")));
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("global-skill")));
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("agent-skill")));
        assertTrue(skills.stream().anyMatch(s -> s.getName().equals("tenant-skill")));
        // 不应该包含其他 Agent 的 Skill
        assertFalse(skills.stream().anyMatch(s -> s.getName().equals("other-agent-skill")));
    }

    @Test
    void testGetSkillsByScope() {
        createTestSkill("global-1", SkillScope.GLOBAL, null);
        createTestSkill("global-2", SkillScope.GLOBAL, null);
        createTestSkill("system-1", SkillScope.SYSTEM, null);

        List<SkillConfigDTO> globalSkills = skillConfigManager.getSkillsByScope(SkillScope.GLOBAL, null);

        assertTrue(globalSkills.size() >= 2);
        assertTrue(globalSkills.stream().allMatch(s -> s.getScope() == SkillScope.GLOBAL));
    }

    @Test
    void testGetSkillByName() {
        createTestSkill("unique-skill", SkillScope.GLOBAL, null);

        Optional<SkillConfigDTO> dto = skillConfigManager.getSkillByName("unique-skill", null);

        assertTrue(dto.isPresent());
        assertEquals("unique-skill", dto.get().getName());
    }

    @Test
    void testGetSkillByName_NotFound() {
        Optional<SkillConfigDTO> dto = skillConfigManager.getSkillByName("non-existent-skill", null);

        assertFalse(dto.isPresent());
    }

    @Test
    void testSkillPrioritySorting() {
        // 创建不同优先级的 Skills
        SkillConfigForm highPriority = createTestSkillForm("high-priority", SkillScope.GLOBAL, null);
        highPriority.getBasicInfo().setPriority(10);
        skillConfigManager.saveSkillConfig(highPriority);

        SkillConfigForm lowPriority = createTestSkillForm("low-priority", SkillScope.GLOBAL, null);
        lowPriority.getBasicInfo().setPriority(100);
        skillConfigManager.saveSkillConfig(lowPriority);

        SkillConfigForm mediumPriority = createTestSkillForm("medium-priority", SkillScope.GLOBAL, null);
        mediumPriority.getBasicInfo().setPriority(50);
        skillConfigManager.saveSkillConfig(mediumPriority);

        // 查询并验证排序
        List<SkillConfigDTO> skills = skillConfigManager.getSkillsForAgent("any-agent", null);

        // 按优先级排序，数值小的在前
        int highPriorityIndex = -1, mediumPriorityIndex = -1, lowPriorityIndex = -1;
        for (int i = 0; i < skills.size(); i++) {
            if (skills.get(i).getName().equals("high-priority")) highPriorityIndex = i;
            if (skills.get(i).getName().equals("medium-priority")) mediumPriorityIndex = i;
            if (skills.get(i).getName().equals("low-priority")) lowPriorityIndex = i;
        }

        assertTrue(highPriorityIndex < mediumPriorityIndex);
        assertTrue(mediumPriorityIndex < lowPriorityIndex);
    }

    // ===== 辅助方法 =====

    private SkillConfigForm createTestSkillForm(String name, SkillScope scope, String agentId) {
        SkillConfigForm form = new SkillConfigForm();

        SkillBasicInfo basicInfo = new SkillBasicInfo();
        basicInfo.setName(name);
        basicInfo.setDescription("Test skill: " + name);
        basicInfo.setScope(scope);
        basicInfo.setAgentId(agentId);
        basicInfo.setStatus(SkillStatus.ENABLED);
        basicInfo.setPriority(50);
        form.setBasicInfo(basicInfo);

        return form;
    }

    private String createTestSkill(String name, SkillScope scope, String agentId) {
        SkillConfigForm form = createTestSkillForm(name, scope, agentId);
        return skillConfigManager.saveSkillConfig(form);
    }

    private String createTestSkillWithTenant(String name, SkillScope scope, String tenantId) {
        SkillConfigForm form = createTestSkillForm(name, scope, null);
        form.setTenantId(tenantId);
        return skillConfigManager.saveSkillConfig(form);
    }
}
