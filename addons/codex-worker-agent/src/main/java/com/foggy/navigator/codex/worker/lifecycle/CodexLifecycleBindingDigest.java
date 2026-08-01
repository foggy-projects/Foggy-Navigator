package com.foggy.navigator.codex.worker.lifecycle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.foggy.navigator.common.termination.TerminationOperationCapability;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Map;
import java.util.TreeMap;

/**
 * Java implementation of the Node lifecycle v1 JCS-safe command binding.
 * All current fields are strings or null, so lexicographically sorted JSON
 * is byte-identical to the Worker canonicalizer.
 */
@Component
public class CodexLifecycleBindingDigest {
    private static final String SCHEMA =
            "NAVIGATOR_LIFECYCLE_BINDING_V1";
    private final ObjectMapper objectMapper;
    private final ObjectMapper canonicalMapper;

    public CodexLifecycleBindingDigest(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.canonicalMapper = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    }

    public String task(
            Map<String, Object> requestBody,
            Map<String, Object> lifecycleContext) {
        Map<String, Object> content = new java.util.LinkedHashMap<>(
                requestBody == null ? Map.of() : requestBody);
        content.remove("lifecycle_context");
        String payloadDigest;
        try {
            payloadDigest = sha256(canonicalMapper.writeValueAsString(content));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "LIFECYCLE_BINDING_JSON_INVALID", error);
        }
        Map<String, Object> binding = new TreeMap<>();
        binding.put("schema", SCHEMA);
        binding.put("ownership_mode", lifecycleContext.get("ownership_mode"));
        binding.put("http_method", "POST");
        binding.put("route_template", "/api/v1/query");
        binding.put("command_kind", lifecycleContext.get("command_kind"));
        binding.put("navigator_task_id",
                lifecycleContext.get("navigator_task_id"));
        binding.put("provider_task_id", null);
        binding.put("dispatch_id", lifecycleContext.get("dispatch_id"));
        binding.put("termination_operation_id",
                lifecycleContext.get("termination_operation_id"));
        binding.put("payload_digest", payloadDigest);
        binding.put("capability_payload_digest", null);
        try {
            return sha256(canonicalMapper.writeValueAsString(binding));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "LIFECYCLE_BINDING_JSON_INVALID", error);
        }
    }

    public String termination(
            Map<String, Object> lifecycleContext,
            String providerTaskId,
            TerminationOperationCapability capability) {
        String payloadDigest = sha256("{}");
        String capabilityDigest = sha256(
                capability.encodedOperation());
        Map<String, Object> binding = new TreeMap<>();
        binding.put("schema", SCHEMA);
        binding.put("ownership_mode",
                lifecycleContext.get("ownership_mode"));
        binding.put("http_method", "POST");
        binding.put("route_template",
                "/api/v1/tasks/{providerTaskId}/abort");
        binding.put("command_kind",
                lifecycleContext.get("command_kind"));
        binding.put("navigator_task_id",
                lifecycleContext.get("navigator_task_id"));
        binding.put("provider_task_id", providerTaskId);
        binding.put("dispatch_id",
                lifecycleContext.get("dispatch_id"));
        binding.put("termination_operation_id",
                lifecycleContext.get("termination_operation_id"));
        binding.put("payload_digest", payloadDigest);
        binding.put("capability_payload_digest",
                capabilityDigest);
        try {
            return sha256(objectMapper.writeValueAsString(binding));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(
                    "LIFECYCLE_BINDING_JSON_INVALID", error);
        }
    }

    private String sha256(String value) {
        try {
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest
                            .getInstance("SHA-256")
                            .digest(value.getBytes(
                                    StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
