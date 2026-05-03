package com.foggy.navigator.business.agent.service.adapter;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

@Slf4j
@RequiredArgsConstructor
public class LocalEchoBusinessFunctionAdapterInvoker implements BusinessFunctionAdapterInvoker {

    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String type) {
        return "echo".equalsIgnoreCase(type);
    }

    @Override
    public BusinessFunctionAdapterResult invoke(BusinessFunctionRuntimeContextDTO context, String inputJson) {
        String configJson = context.getAdapterConfigJson();

        if (!StringUtils.hasText(configJson)) {
            throw new IllegalArgumentException("Adapter config is missing or blank");
        }

        try {
            JsonNode configNode = objectMapper.readTree(configJson);

            // Check for type or adapterType (case-insensitive for value)
            String type = null;
            if (configNode.has("type") && !configNode.get("type").isNull()) {
                type = configNode.get("type").asText();
            } else if (configNode.has("adapterType") && !configNode.get("adapterType").isNull()) {
                type = configNode.get("adapterType").asText();
            }

            if (!StringUtils.hasText(type)) {
                throw new IllegalArgumentException("Adapter type not specified in config");
            }

            if ("echo".equalsIgnoreCase(type)) {
                log.info("Executing local echo adapter for function {}", context.getFunction().getFunctionId());
                return BusinessFunctionAdapterResult.success(inputJson);
            } else {
                throw new IllegalArgumentException("Unsupported adapter type: " + type);
            }

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse adapter config for function {}", context.getFunction().getFunctionId(), e);
            throw new IllegalArgumentException("Invalid adapter config JSON", e);
        }
    }
}
