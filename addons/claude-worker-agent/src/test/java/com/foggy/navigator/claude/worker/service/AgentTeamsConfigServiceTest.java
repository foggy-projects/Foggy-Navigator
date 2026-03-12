package com.foggy.navigator.claude.worker.service;

import com.foggy.navigator.claude.worker.model.dto.AgentTeamsConfigDTO;
import com.foggy.navigator.claude.worker.model.entity.AgentTeamsConfigEntity;
import com.foggy.navigator.claude.worker.model.form.CreateAgentTeamsConfigForm;
import com.foggy.navigator.claude.worker.model.form.UpdateAgentTeamsConfigForm;
import com.foggy.navigator.claude.worker.repository.AgentTeamsConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AgentTeamsConfigService 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class AgentTeamsConfigServiceTest {

    @Mock private AgentTeamsConfigRepository configRepository;

    @InjectMocks private AgentTeamsConfigService service;

    // ---- JSON 校验 ----

    @Test
    void createConfig_validJson_accepted() {
        String json = """
                {"team-lead": {"role": "lead"}, "researcher": {"role": "research"}}
                """;
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("My Config");
        form.setConfig(json);

        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentTeamsConfigDTO dto = service.createConfig("dir-1", "u1", form);
        assertNotNull(dto);
        assertEquals("My Config", dto.getName());
        verify(configRepository).save(any());
    }

    @Test
    void createConfig_invalidJson_throws() {
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("Bad");
        form.setConfig("{invalid json}");

        assertThrows(IllegalArgumentException.class,
                () -> service.createConfig("dir-1", "u1", form));
    }

    @Test
    void createConfig_emptyJson_throws() {
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("Empty");
        form.setConfig("   ");

        assertThrows(IllegalArgumentException.class,
                () -> service.createConfig("dir-1", "u1", form));
    }

    @Test
    void createConfig_emptyObject_throws() {
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("Empty Object");
        form.setConfig("{}");

        assertThrows(IllegalArgumentException.class,
                () -> service.createConfig("dir-1", "u1", form));
    }

    @Test
    void createConfig_nullConfig_throws() {
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("Null");
        form.setConfig(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.createConfig("dir-1", "u1", form));
    }

    // ---- 默认配置管理 ----

    @Test
    void createConfig_isDefault_clearsOtherDefaults() {
        AgentTeamsConfigEntity existing = buildEntity("cfg-old", "dir-1", "u1");
        existing.setIsDefault(true);
        when(configRepository.findByDirectoryIdAndUserIdAndIsDefaultTrue("dir-1", "u1"))
                .thenReturn(Optional.of(existing));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("New Default");
        form.setConfig("{\"agent\": {}}");
        form.setIsDefault(true);

        service.createConfig("dir-1", "u1", form);

        assertFalse(existing.getIsDefault());
        // save called for: clearing old default + saving new config
        verify(configRepository, atLeast(2)).save(any());
    }

    @Test
    void createConfig_notDefault_preservesExisting() {
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("Not Default");
        form.setConfig("{\"agent\": {}}");
        form.setIsDefault(false);

        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.createConfig("dir-1", "u1", form);

        // Should NOT query for existing defaults
        verify(configRepository, never()).findByDirectoryIdAndUserIdAndIsDefaultTrue(any(), any());
    }

    // ---- Agent 名称提取 ----

    @Test
    void createConfig_agentNames_parsedFromJsonKeys() {
        CreateAgentTeamsConfigForm form = new CreateAgentTeamsConfigForm();
        form.setName("Named");
        form.setConfig("{\"team-lead\": {}, \"researcher\": {}, \"coder\": {}}");

        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AgentTeamsConfigDTO dto = service.createConfig("dir-1", "u1", form);

        assertNotNull(dto.getAgentNames());
        assertEquals(3, dto.getAgentNames().size());
        assertTrue(dto.getAgentNames().contains("team-lead"));
        assertTrue(dto.getAgentNames().contains("researcher"));
        assertTrue(dto.getAgentNames().contains("coder"));
    }

    // ---- CRUD ----

    @Test
    void getConfig_found_returnsDTO() {
        AgentTeamsConfigEntity entity = buildEntity("cfg-1", "dir-1", "u1");
        entity.setConfig("{\"agent\": {}}");
        when(configRepository.findByConfigIdAndUserId("cfg-1", "u1")).thenReturn(Optional.of(entity));

        AgentTeamsConfigDTO dto = service.getConfig("cfg-1", "u1");
        assertEquals("cfg-1", dto.getConfigId());
    }

    @Test
    void getConfig_notFound_throws() {
        when(configRepository.findByConfigIdAndUserId("cfg-99", "u1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.getConfig("cfg-99", "u1"));
    }

    @Test
    void listConfigs_orderedResult() {
        AgentTeamsConfigEntity e1 = buildEntity("cfg-1", "dir-1", "u1");
        e1.setConfig("{\"a\": {}}");
        AgentTeamsConfigEntity e2 = buildEntity("cfg-2", "dir-1", "u1");
        e2.setConfig("{\"b\": {}}");
        when(configRepository.findByDirectoryIdAndUserIdOrderByCreatedAtAsc("dir-1", "u1"))
                .thenReturn(List.of(e1, e2));

        List<AgentTeamsConfigDTO> result = service.listConfigs("dir-1", "u1");
        assertEquals(2, result.size());
    }

    @Test
    void updateConfig_partialUpdate() {
        AgentTeamsConfigEntity entity = buildEntity("cfg-1", "dir-1", "u1");
        entity.setConfig("{\"old\": {}}");
        when(configRepository.findByConfigIdAndUserId("cfg-1", "u1")).thenReturn(Optional.of(entity));
        when(configRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateAgentTeamsConfigForm form = new UpdateAgentTeamsConfigForm();
        form.setName("Updated Name");
        // config is null → should not change

        service.updateConfig("cfg-1", "u1", form);

        assertEquals("Updated Name", entity.getName());
        assertEquals("{\"old\": {}}", entity.getConfig()); // unchanged
    }

    @Test
    void updateConfig_invalidJson_throws() {
        AgentTeamsConfigEntity entity = buildEntity("cfg-1", "dir-1", "u1");
        entity.setConfig("{\"old\": {}}");
        when(configRepository.findByConfigIdAndUserId("cfg-1", "u1")).thenReturn(Optional.of(entity));

        UpdateAgentTeamsConfigForm form = new UpdateAgentTeamsConfigForm();
        form.setConfig("{broken json}");

        assertThrows(IllegalArgumentException.class,
                () -> service.updateConfig("cfg-1", "u1", form));
    }

    @Test
    void deleteConfig_removesFromRepo() {
        AgentTeamsConfigEntity entity = buildEntity("cfg-1", "dir-1", "u1");
        when(configRepository.findByConfigIdAndUserId("cfg-1", "u1")).thenReturn(Optional.of(entity));

        service.deleteConfig("cfg-1", "u1");

        verify(configRepository).delete(entity);
    }

    @Test
    void deleteConfig_notFound_throws() {
        when(configRepository.findByConfigIdAndUserId("cfg-99", "u1")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.deleteConfig("cfg-99", "u1"));
    }

    @Test
    void existsForDirectory_delegatesToRepo() {
        when(configRepository.existsByDirectoryIdAndUserId("dir-1", "u1")).thenReturn(true);
        assertTrue(service.existsForDirectory("dir-1", "u1"));

        when(configRepository.existsByDirectoryIdAndUserId("dir-2", "u1")).thenReturn(false);
        assertFalse(service.existsForDirectory("dir-2", "u1"));
    }

    @Test
    void getDefaultConfig_delegatesToRepo() {
        AgentTeamsConfigEntity entity = buildEntity("cfg-1", "dir-1", "u1");
        when(configRepository.findByDirectoryIdAndUserIdAndIsDefaultTrue("dir-1", "u1"))
                .thenReturn(Optional.of(entity));

        Optional<AgentTeamsConfigEntity> result = service.getDefaultConfig("dir-1", "u1");
        assertTrue(result.isPresent());
    }

    // ---- helper ----

    private AgentTeamsConfigEntity buildEntity(String configId, String directoryId, String userId) {
        AgentTeamsConfigEntity entity = new AgentTeamsConfigEntity();
        entity.setConfigId(configId);
        entity.setDirectoryId(directoryId);
        entity.setUserId(userId);
        entity.setName("Config");
        entity.setConfig("{}");
        entity.setIsDefault(false);
        return entity;
    }
}
