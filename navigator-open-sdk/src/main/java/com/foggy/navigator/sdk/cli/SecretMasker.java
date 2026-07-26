package com.foggy.navigator.sdk.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.UnaryOperator;

final class SecretMasker {
    private static final String REDACTED = "[REDACTED]";
    private static final Set<String> SENSITIVE_JSON_SUFFIXES =
            Set.of("TOKEN", "SECRET", "PASSWORD", "CREDENTIAL", "AUTHORIZATION");

    private SecretMasker() {
    }

    static String mask(String value) {
        if (value == null || value.isBlank()) {
            return "(empty)";
        }
        String trimmed = value.trim();
        String hash = sha256Prefix(trimmed);
        if (trimmed.length() <= 8) {
            return "*** sha256=" + hash;
        }
        return trimmed.substring(0, 4) + "..." + trimmed.substring(trimmed.length() - 4)
                + " sha256=" + hash;
    }

    static String redactKnownSecrets(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty() || secrets == null || secrets.isEmpty()) {
            return text;
        }
        String redacted = text;
        for (String secret : secrets) {
            if (secret != null && !secret.isBlank()) {
                redacted = redacted.replace(secret, REDACTED);
            }
        }
        return redacted;
    }

    static JsonNode redactJson(ObjectMapper objectMapper,
                               Object value,
                               UnaryOperator<String> textRedactor) {
        JsonNode root = objectMapper.valueToTree(value);
        return redactJsonNode(root, textRedactor);
    }

    private static JsonNode redactJsonNode(JsonNode node, UnaryOperator<String> textRedactor) {
        if (node == null) {
            return null;
        }
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            List<String> fieldNames = new ArrayList<>();
            object.fieldNames().forEachRemaining(fieldNames::add);
            for (String fieldName : fieldNames) {
                JsonNode fieldValue = object.get(fieldName);
                object.set(fieldName, isSensitiveJsonField(fieldName)
                        ? TextNode.valueOf(REDACTED)
                        : redactJsonNode(fieldValue, textRedactor));
            }
            return object;
        }
        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int index = 0; index < array.size(); index++) {
                array.set(index, redactJsonNode(array.get(index), textRedactor));
            }
            return array;
        }
        if (node.isTextual()) {
            return TextNode.valueOf(textRedactor.apply(node.textValue()));
        }
        return node;
    }

    private static boolean isSensitiveJsonField(String fieldName) {
        if (fieldName == null || fieldName.isBlank()) {
            return false;
        }
        String separated = fieldName.replaceAll("([a-z0-9])([A-Z])", "$1_$2");
        String[] segments = separated.toUpperCase(Locale.ROOT).split("[^A-Z0-9]+");
        if (segments.length == 0) {
            return false;
        }
        String suffix = segments[segments.length - 1];
        if (SENSITIVE_JSON_SUFFIXES.contains(suffix)) {
            return true;
        }
        return "KEY".equals(suffix) && segments.length > 1;
    }

    static String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return sha256(value.trim(), Integer.MAX_VALUE);
    }

    private static String sha256Prefix(String value) {
        return sha256(value, 6);
    }

    private static String sha256(String value, int maxBytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < maxBytes && i < bytes.length; i++) {
                sb.append(String.format("%02x", bytes[i]));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
