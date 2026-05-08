package com.foggy.navigator.business.agent.service.adapter;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class CompositeBusinessFunctionAdapterInvoker implements BusinessFunctionAdapterInvoker {

    private final List<BusinessFunctionAdapterInvoker> delegates;
    private final ObjectMapper objectMapper;

    @Override
    public boolean supports(String type) {
        return true;
    }

    @Override
    public BusinessFunctionAdapterResult invoke(BusinessFunctionRuntimeContextDTO context, String inputJson) {
        String configJson = context.getAdapterConfigJson();

        if (!StringUtils.hasText(configJson)) {
            throw new IllegalArgumentException("Adapter config is missing or blank");
        }

        try {
            JsonNode configNode = objectMapper.readTree(configJson);

            // Extract type
            String type = null;
            if (configNode.has("type") && !configNode.get("type").isNull()) {
                type = configNode.get("type").asText();
            } else if (configNode.has("adapterType") && !configNode.get("adapterType").isNull()) {
                type = configNode.get("adapterType").asText();
            }

            if (!StringUtils.hasText(type)) {
                throw new IllegalArgumentException("Adapter type not specified in config");
            }

            for (BusinessFunctionAdapterInvoker delegate : delegates) {
                if (delegate.supports(type)) {
                    return delegate.invoke(context, inputJson);
                }
            }

            throw new IllegalArgumentException("Unsupported adapter type: " + type);

        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse adapter config for function {}", context.getFunction().getFunctionId(), e);
            throw new IllegalArgumentException("Invalid adapter config JSON", e);
        }
    }
}
