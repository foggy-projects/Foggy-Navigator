package com.foggy.navigator.tutor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.foggy.navigator.agent.framework.core.AgentInfo;
import com.foggy.navigator.agent.framework.core.AgentRegistry;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class TutorAgentRegistrar {

    private final AgentRegistry agentRegistry;

    @PostConstruct
    public void register() {
        // 仅当 DB 中不存在时从 YAML 初始化（YAML 作为种子，DB 为 source of truth）
        seedAgentIfAbsent("/agent-config/tutor-agent.yml");
        seedAgentIfAbsent("/agent-config/coding-agent.yml");
    }

    private void seedAgentIfAbsent(String configPath) {
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            yamlMapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);

            InputStream input = getClass().getResourceAsStream(configPath);
            if (input == null) {
                log.warn("{} not found on classpath", configPath);
                return;
            }

            JsonNode tree = yamlMapper.readTree(input);
            JsonNode agentNode = tree.get("agent");
            if (agentNode == null) {
                log.warn("No 'agent' node in {}", configPath);
                return;
            }

            // Remove problematic delegation section that has schema mismatch
            if (agentNode.has("delegation")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) agentNode).remove("delegation");
            }

            AgentConfig config = yamlMapper.treeToValue(agentNode, AgentConfig.class);

            if (agentRegistry.exists(config.getId())) {
                // Agent 已存在，同步 system prompt（YAML 为模板，允许覆盖更新）
                syncSystemPrompt(config);
                return;
            }

            agentRegistry.register(config);
            log.info("Seeded agent from YAML: id={}, name={}", config.getId(), config.getName());
        } catch (Exception e) {
            log.error("Failed to seed agent from {}", configPath, e);
        }
    }

    /**
     * 同步 system prompt：YAML 有变更时更新到 DB
     */
    private void syncSystemPrompt(AgentConfig yamlConfig) {
        AgentInfo existing = agentRegistry.findById(yamlConfig.getId());
        if (existing == null || existing.getConfig() == null) {
            return;
        }

        String yamlPrompt = yamlConfig.getModel() != null ? yamlConfig.getModel().getSystemPrompt() : null;
        String dbPrompt = existing.getConfig().getModel() != null
                ? existing.getConfig().getModel().getSystemPrompt() : null;

        if (yamlPrompt != null && !yamlPrompt.equals(dbPrompt)) {
            // 用 YAML 版 system prompt 覆盖 DB 版，其余字段保持 DB 值
            existing.getConfig().getModel().setSystemPrompt(yamlPrompt);
            agentRegistry.register(existing.getConfig());
            log.info("Synced system prompt from YAML: id={}", yamlConfig.getId());
        } else {
            log.info("Agent system prompt unchanged, skipping sync: id={}", yamlConfig.getId());
        }
    }
}
