package com.foggy.navigator.agent.framework.core.impl;

import com.foggy.navigator.agent.framework.core.AgentConfigException;
import com.foggy.navigator.agent.framework.core.ValidationResult;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DefaultAgentConfigLoader 单元测试 — L1
 */
class DefaultAgentConfigLoaderTest {

    private DefaultAgentConfigLoader loader;

    @BeforeEach
    void setUp() {
        loader = new DefaultAgentConfigLoader();
    }

    // ---- JSON 解析 ----

    @Test
    void loadFromJson_validConfig() {
        String json = """
                {
                  "id": "tutor-agent",
                  "name": "Tutor Agent",
                  "type": "system",
                  "description": "A helpful tutor"
                }
                """;
        AgentConfig config = loader.loadFromJson(json);
        assertEquals("tutor-agent", config.getId());
        assertEquals("Tutor Agent", config.getName());
        assertEquals("system", config.getType());
    }

    @Test
    void loadFromJson_invalidJson_throwsException() {
        assertThrows(AgentConfigException.class, () -> loader.loadFromJson("{invalid}"));
    }

    // ---- YAML 解析 ----

    @Test
    void loadFromYaml_validConfig() {
        String yaml = """
                id: data-agent
                name: Data Agent
                type: user
                description: Data analysis agent
                """;
        AgentConfig config = loader.loadFromYaml(yaml);
        assertEquals("data-agent", config.getId());
        assertEquals("Data Agent", config.getName());
        assertEquals("user", config.getType());
    }

    @Test
    void loadFromYaml_invalidYaml_throwsException() {
        assertThrows(AgentConfigException.class, () -> loader.loadFromYaml(":: invalid yaml ::"));
    }

    // ---- 从文件加载 ----

    @Test
    void load_jsonFile(@TempDir Path tempDir) throws Exception {
        Path jsonFile = tempDir.resolve("agent.json");
        Files.writeString(jsonFile, """
                {"id": "test-agent", "name": "Test", "type": "system"}
                """);
        AgentConfig config = loader.load(jsonFile.toString());
        assertEquals("test-agent", config.getId());
    }

    @Test
    void load_yamlFile(@TempDir Path tempDir) throws Exception {
        Path yamlFile = tempDir.resolve("agent.yml");
        Files.writeString(yamlFile, """
                id: yaml-agent
                name: YAML Agent
                type: user
                """);
        AgentConfig config = loader.load(yamlFile.toString());
        assertEquals("yaml-agent", config.getId());
    }

    @Test
    void load_unsupportedFormat_throwsException(@TempDir Path tempDir) throws Exception {
        Path txtFile = tempDir.resolve("agent.txt");
        Files.writeString(txtFile, "id=test");
        assertThrows(AgentConfigException.class, () -> loader.load(txtFile.toString()));
    }

    @Test
    void load_nonExistentFile_throwsException() {
        assertThrows(AgentConfigException.class, () -> loader.load("/nonexistent/agent.json"));
    }

    // ---- 验证 ----

    @Test
    void validate_validConfig() {
        AgentConfig config = new AgentConfig();
        config.setId("agent-1");
        config.setName("Agent One");
        config.setType("system");

        ValidationResult result = loader.validate(config);
        assertTrue(result.isValid());
    }

    @Test
    void validate_missingId() {
        AgentConfig config = new AgentConfig();
        config.setName("Agent");
        config.setType("system");

        ValidationResult result = loader.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("ID")));
    }

    @Test
    void validate_missingName() {
        AgentConfig config = new AgentConfig();
        config.setId("agent-1");
        config.setType("system");

        ValidationResult result = loader.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("name")));
    }

    @Test
    void validate_invalidType() {
        AgentConfig config = new AgentConfig();
        config.setId("agent-1");
        config.setName("Agent");
        config.setType("invalid");

        ValidationResult result = loader.validate(config);
        assertFalse(result.isValid());
        assertTrue(result.getErrors().stream().anyMatch(e -> e.contains("type")));
    }

    @Test
    void validate_missingModel_warningOnly() {
        AgentConfig config = new AgentConfig();
        config.setId("agent-1");
        config.setName("Agent");
        config.setType("user");
        config.setModel(null);

        ValidationResult result = loader.validate(config);
        assertTrue(result.isValid()); // 只是警告
        assertFalse(result.getWarnings().isEmpty());
    }

    // ---- 序列化 ----

    @Test
    void toJson_roundTrip() {
        AgentConfig config = new AgentConfig();
        config.setId("rt-agent");
        config.setName("RoundTrip");
        config.setType("system");

        String json = loader.toJson(config);
        assertNotNull(json);
        assertTrue(json.contains("rt-agent"));

        AgentConfig loaded = loader.loadFromJson(json);
        assertEquals("rt-agent", loaded.getId());
    }

    @Test
    void toYaml_roundTrip() {
        AgentConfig config = new AgentConfig();
        config.setId("yaml-rt");
        config.setName("YAML RoundTrip");
        config.setType("user");

        String yaml = loader.toYaml(config);
        assertNotNull(yaml);
        assertTrue(yaml.contains("yaml-rt"));

        AgentConfig loaded = loader.loadFromYaml(yaml);
        assertEquals("yaml-rt", loaded.getId());
    }
}
