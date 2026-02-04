package com.foggy.navigator.tutor;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
        try {
            ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
            yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            yamlMapper.configure(DeserializationFeature.FAIL_ON_INVALID_SUBTYPE, false);

            InputStream input = getClass().getResourceAsStream("/agent-config/tutor-agent.yml");
            if (input == null) {
                log.warn("tutor-agent.yml not found on classpath");
                return;
            }

            JsonNode tree = yamlMapper.readTree(input);
            JsonNode agentNode = tree.get("agent");
            if (agentNode == null) {
                log.warn("No 'agent' node in tutor-agent.yml");
                return;
            }

            // Remove problematic delegation section that has schema mismatch
            if (agentNode.has("delegation")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) agentNode).remove("delegation");
            }

            AgentConfig config = yamlMapper.treeToValue(agentNode, AgentConfig.class);
            agentRegistry.register(config);
            log.info("Registered agent: id={}, name={}", config.getId(), config.getName());
        } catch (Exception e) {
            log.error("Failed to register tutor-agent", e);
        }
    }
}
