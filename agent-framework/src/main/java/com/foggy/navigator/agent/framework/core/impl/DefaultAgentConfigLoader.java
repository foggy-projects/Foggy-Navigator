package com.foggy.navigator.agent.framework.core.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.foggy.navigator.agent.framework.core.AgentConfigException;
import com.foggy.navigator.agent.framework.core.AgentConfigLoader;
import com.foggy.navigator.agent.framework.core.ValidationResult;
import com.foggy.navigator.agent.framework.core.model.AgentConfig;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 默认Agent配置加载器实现
 */
@Component
public class DefaultAgentConfigLoader implements AgentConfigLoader {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;

    public DefaultAgentConfigLoader() {
        this.jsonMapper = new ObjectMapper();
        this.jsonMapper.registerModule(new JavaTimeModule());

        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.yamlMapper.registerModule(new JavaTimeModule());
    }

    @Override
    public AgentConfig loadFromJson(String json) throws AgentConfigException {
        try {
            return jsonMapper.readValue(json, AgentConfig.class);
        } catch (Exception e) {
            throw new AgentConfigException("Failed to parse JSON config: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentConfig loadFromYaml(String yaml) throws AgentConfigException {
        try {
            return yamlMapper.readValue(yaml, AgentConfig.class);
        } catch (Exception e) {
            throw new AgentConfigException("Failed to parse YAML config: " + e.getMessage(), e);
        }
    }

    @Override
    public AgentConfig load(String path) throws AgentConfigException {
        try {
            String content = Files.readString(Path.of(path));
            if (path.endsWith(".json")) {
                return loadFromJson(content);
            } else if (path.endsWith(".yml") || path.endsWith(".yaml")) {
                return loadFromYaml(content);
            } else {
                throw new AgentConfigException("Unsupported file format: " + path);
            }
        } catch (IOException e) {
            throw new AgentConfigException("Failed to read config file: " + path, e);
        }
    }

    @Override
    public ValidationResult validate(AgentConfig config) {
        ValidationResult result = ValidationResult.success();

        if (config.getId() == null || config.getId().isBlank()) {
            result.addError("Agent ID is required");
        }
        if (config.getName() == null || config.getName().isBlank()) {
            result.addError("Agent name is required");
        }
        if (config.getType() == null || config.getType().isBlank()) {
            result.addError("Agent type is required");
        } else if (!config.getType().equals("system") && !config.getType().equals("user")) {
            result.addError("Agent type must be 'system' or 'user'");
        }
        if (config.getModel() == null) {
            result.addWarning("Model config is not specified, will use default");
        }

        return result;
    }

    @Override
    public String toJson(AgentConfig config) {
        try {
            return jsonMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (Exception e) {
            throw new AgentConfigException("Failed to serialize config to JSON", e);
        }
    }

    @Override
    public String toYaml(AgentConfig config) {
        try {
            return yamlMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (Exception e) {
            throw new AgentConfigException("Failed to serialize config to YAML", e);
        }
    }
}
