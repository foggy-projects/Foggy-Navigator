package com.foggy.navigator.business.agent.service.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.util.StringUtils;

/**
 * A minimal JSON path evaluator that safely extracts string values from a Jackson JsonNode tree.
 * Only supports simple dot notation: $.input.order_id, $.context.client_app_id
 */
public class SimpleJsonPathEvaluator {

    /**
     * Evaluates a template string. If it starts with "$.", it resolves the path against rootNode.
     * Otherwise, it returns the template verbatim.
     *
     * @param template the template or literal
     * @param rootNode the root JSON node
     * @return the resolved string value, or null if path not found
     */
    public static String evaluate(String template, JsonNode rootNode) {
        if (!StringUtils.hasText(template) || !template.startsWith("$.")) {
            return template; // Literal or empty
        }

        JsonNode current = evaluateNode(template, rootNode);
        if (current == null || current.isNull()) {
            return null;
        }

        if (current.isValueNode()) {
            return current.asText();
        }

        // Return JSON string for object/array nodes when a string context is required.
        return current.toString();
    }

    /**
     * Resolves a JSON path template to the original JsonNode. Literal templates return null.
     */
    public static JsonNode evaluateNode(String template, JsonNode rootNode) {
        if (!StringUtils.hasText(template) || !template.startsWith("$.")) {
            return null;
        }

        String path = template.substring(2); // Remove "$."
        String[] parts = path.split("\\.");

        JsonNode current = rootNode;
        for (String part : parts) {
            if (current == null || !current.isObject()) {
                return null;
            }
            current = current.get(part);
        }

        if (current == null || current.isNull()) {
            return null;
        }
        return current;
    }
}
