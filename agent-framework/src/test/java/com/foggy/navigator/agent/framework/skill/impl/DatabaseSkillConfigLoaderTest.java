package com.foggy.navigator.agent.framework.skill.impl;

import com.foggy.navigator.agent.framework.skill.Skill;
import com.foggy.navigator.common.dto.SkillConfigDTO;
import com.foggy.navigator.common.enums.SkillScope;
import com.foggy.navigator.common.enums.SkillStatus;
import com.foggy.navigator.spi.config.SkillConfigManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseSkillConfigLoaderTest {

    @Mock
    private SkillConfigManager skillConfigManager;

    private DatabaseSkillConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DatabaseSkillConfigLoader(skillConfigManager);
    }

    @Test
    void loadSkillsForAgent_shouldReturnSkillsFromManager() {
        // Arrange
        String agentId = "test-agent";
        String tenantId = "tenant-001";
        List<SkillConfigDTO> mockDtos = Arrays.asList(
            createMockDTO("skill-1", "Skill 1", SkillScope.GLOBAL),
            createMockDTO("skill-2", "Skill 2", SkillScope.AGENT)
        );
        when(skillConfigManager.getSkillsForAgent(agentId, tenantId)).thenReturn(mockDtos);

        // Act
        List<Skill> skills = loader.loadSkillsForAgent(agentId, tenantId);

        // Assert
        assertEquals(2, skills.size());
        assertEquals("skill-1", skills.get(0).getId());
        assertEquals("Skill 1", skills.get(0).getName());
        assertEquals("skill-2", skills.get(1).getId());
        verify(skillConfigManager).getSkillsForAgent(agentId, tenantId);
    }

    @Test
    void loadSkillsForAgent_shouldCacheResults() {
        // Arrange
        String agentId = "test-agent";
        String tenantId = "tenant-001";
        List<SkillConfigDTO> mockDtos = Arrays.asList(
            createMockDTO("skill-1", "Skill 1", SkillScope.GLOBAL)
        );
        when(skillConfigManager.getSkillsForAgent(agentId, tenantId)).thenReturn(mockDtos);

        // Act - 调用两次
        List<Skill> firstCall = loader.loadSkillsForAgent(agentId, tenantId);
        List<Skill> secondCall = loader.loadSkillsForAgent(agentId, tenantId);

        // Assert - 只应该调用一次 SkillConfigManager
        assertEquals(1, firstCall.size());
        assertEquals(1, secondCall.size());
        verify(skillConfigManager, times(1)).getSkillsForAgent(agentId, tenantId);
    }

    @Test
    void loadSkillsForAgent_shouldHandleDifferentTenants() {
        // Arrange
        String agentId = "test-agent";
        List<SkillConfigDTO> tenant1Dtos = Arrays.asList(
            createMockDTO("skill-t1", "Tenant 1 Skill", SkillScope.TENANT)
        );
        List<SkillConfigDTO> tenant2Dtos = Arrays.asList(
            createMockDTO("skill-t2", "Tenant 2 Skill", SkillScope.TENANT)
        );
        when(skillConfigManager.getSkillsForAgent(agentId, "tenant-1")).thenReturn(tenant1Dtos);
        when(skillConfigManager.getSkillsForAgent(agentId, "tenant-2")).thenReturn(tenant2Dtos);

        // Act
        List<Skill> tenant1Skills = loader.loadSkillsForAgent(agentId, "tenant-1");
        List<Skill> tenant2Skills = loader.loadSkillsForAgent(agentId, "tenant-2");

        // Assert
        assertEquals("skill-t1", tenant1Skills.get(0).getId());
        assertEquals("skill-t2", tenant2Skills.get(0).getId());
    }

    @Test
    void loadSkill_shouldReturnSkillFromManager() {
        // Arrange
        String skillId = "skill-123";
        SkillConfigDTO mockDto = createMockDTO(skillId, "Test Skill", SkillScope.GLOBAL);
        mockDto.setTriggerKeywords(Arrays.asList("keyword1", "keyword2"));
        mockDto.setIntents(Arrays.asList("intent1"));
        mockDto.setExecutionLogic("1. Step one\n2. Step two");
        mockDto.setOutputFormat("JSON");
        when(skillConfigManager.getSkillConfig(skillId)).thenReturn(Optional.of(mockDto));

        // Act
        Skill skill = loader.loadSkill(skillId);

        // Assert
        assertNotNull(skill);
        assertEquals(skillId, skill.getId());
        assertEquals("Test Skill", skill.getName());
        assertEquals(2, skill.getTriggerKeywords().size());
        assertEquals(1, skill.getIntents().size());
        assertEquals("1. Step one\n2. Step two", skill.getExecutionLogic());
        assertEquals("JSON", skill.getOutputFormat());
        verify(skillConfigManager).getSkillConfig(skillId);
    }

    @Test
    void loadSkill_shouldReturnNullWhenNotFound() {
        // Arrange
        String skillId = "non-existent";
        when(skillConfigManager.getSkillConfig(skillId)).thenReturn(Optional.empty());

        // Act
        Skill skill = loader.loadSkill(skillId);

        // Assert
        assertNull(skill);
    }

    @Test
    void refreshSkills_shouldClearCache() {
        // Arrange
        String agentId = "test-agent";
        String tenantId = "tenant-001";
        List<SkillConfigDTO> mockDtos = Arrays.asList(
            createMockDTO("skill-1", "Skill 1", SkillScope.GLOBAL)
        );
        when(skillConfigManager.getSkillsForAgent(agentId, tenantId)).thenReturn(mockDtos);

        // 先加载一次，建立缓存
        loader.loadSkillsForAgent(agentId, tenantId);
        verify(skillConfigManager, times(1)).getSkillsForAgent(agentId, tenantId);

        // Act - 刷新缓存
        loader.refreshSkills(agentId);

        // 再次加载，应该重新调用 Manager
        loader.loadSkillsForAgent(agentId, tenantId);

        // Assert
        verify(skillConfigManager, times(2)).getSkillsForAgent(agentId, tenantId);
    }

    @Test
    void clearCache_shouldClearAllCache() {
        // Arrange
        when(skillConfigManager.getSkillsForAgent(anyString(), anyString()))
            .thenReturn(Arrays.asList(createMockDTO("skill", "Skill", SkillScope.GLOBAL)));

        // 加载多个 Agent 的 Skills
        loader.loadSkillsForAgent("agent-1", "tenant-1");
        loader.loadSkillsForAgent("agent-2", "tenant-2");
        verify(skillConfigManager, times(2)).getSkillsForAgent(anyString(), anyString());

        // Act - 清除所有缓存
        loader.clearCache();

        // 再次加载，所有都应该重新调用
        loader.loadSkillsForAgent("agent-1", "tenant-1");
        loader.loadSkillsForAgent("agent-2", "tenant-2");

        // Assert
        verify(skillConfigManager, times(4)).getSkillsForAgent(anyString(), anyString());
    }

    @Test
    void loadSkillsForAgent_shouldConvertDtoToSkillCorrectly() {
        // Arrange
        SkillConfigDTO dto = new SkillConfigDTO();
        dto.setId("skill-123");
        dto.setName("Test Skill");
        dto.setAgentId("agent-1");
        dto.setDescription("A test skill");
        dto.setScope(SkillScope.AGENT);
        dto.setStatus(SkillStatus.ENABLED);
        dto.setTriggerKeywords(Arrays.asList("trigger1", "trigger2"));
        dto.setIntents(Arrays.asList("intent1", "intent2"));
        dto.setExecutionLogic("Do something");
        dto.setOutputFormat("Markdown");
        dto.setDelegationCondition("When needed");
        dto.setPriority(10);
        dto.setCreatedAt(LocalDateTime.now());

        when(skillConfigManager.getSkillsForAgent("agent-1", "tenant-1"))
            .thenReturn(Arrays.asList(dto));

        // Act
        List<Skill> skills = loader.loadSkillsForAgent("agent-1", "tenant-1");

        // Assert
        assertEquals(1, skills.size());
        Skill skill = skills.get(0);
        assertEquals("skill-123", skill.getId());
        assertEquals("Test Skill", skill.getName());
        assertEquals("agent-1", skill.getAgentId());
        assertEquals("A test skill", skill.getDescription());
        assertEquals(2, skill.getTriggerKeywords().size());
        assertEquals(2, skill.getIntents().size());
        assertEquals("Do something", skill.getExecutionLogic());
        assertEquals("Markdown", skill.getOutputFormat());
        assertEquals("When needed", skill.getDelegationCondition());
        assertNotNull(skill.getLoadedAt());
    }

    @Test
    void loadSkillsForAgent_shouldHandleNullTenant() {
        // Arrange
        String agentId = "test-agent";
        List<SkillConfigDTO> mockDtos = Arrays.asList(
            createMockDTO("skill-1", "Skill 1", SkillScope.GLOBAL)
        );
        when(skillConfigManager.getSkillsForAgent(agentId, null)).thenReturn(mockDtos);

        // Act
        List<Skill> skills = loader.loadSkillsForAgent(agentId, null);

        // Assert
        assertEquals(1, skills.size());
        verify(skillConfigManager).getSkillsForAgent(agentId, null);
    }

    // ===== 辅助方法 =====

    private SkillConfigDTO createMockDTO(String id, String name, SkillScope scope) {
        SkillConfigDTO dto = new SkillConfigDTO();
        dto.setId(id);
        dto.setName(name);
        dto.setScope(scope);
        dto.setStatus(SkillStatus.ENABLED);
        dto.setCreatedAt(LocalDateTime.now());
        return dto;
    }
}
