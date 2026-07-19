package com.foggy.navigator.common.authorization.preseed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** Deterministic SHA-256 over a recursively key-sorted envelope without checksum. */
final class PreseedInventoryCanonicalizer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PreseedInventoryCanonicalizer() {
    }

    static String checksum(JsonNode envelope) {
        JsonNode canonical = canonicalizeEnvelope(envelope);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(MAPPER.writeValueAsString(canonical).getBytes(StandardCharsets.UTF_8));
            return toHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot canonicalize pre-seed inventory", exception);
        }
    }

    private static JsonNode canonicalizeEnvelope(JsonNode envelope) {
        if (!envelope.isObject()) {
            return canonicalize(envelope);
        }
        ObjectNode withoutChecksum = JsonNodeFactory.instance.objectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = envelope.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            if (!"checksum".equals(field.getKey())) {
                withoutChecksum.set(field.getKey(), field.getValue());
            }
        }
        return canonicalize(withoutChecksum);
    }

    private static JsonNode canonicalize(JsonNode value) {
        if (value.isObject()) {
            ObjectNode result = JsonNodeFactory.instance.objectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            value.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), entry.getValue()));
            sorted.forEach((key, child) -> result.set(key, canonicalize(child)));
            return result;
        }
        if (value.isArray()) {
            ArrayNode result = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : value) {
                result.add(canonicalize(child));
            }
            return result;
        }
        return value.deepCopy();
    }

    private static String toHex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            hex.append(Character.forDigit((value >>> 4) & 0x0f, 16));
            hex.append(Character.forDigit(value & 0x0f, 16));
        }
        return hex.toString();
    }
}
