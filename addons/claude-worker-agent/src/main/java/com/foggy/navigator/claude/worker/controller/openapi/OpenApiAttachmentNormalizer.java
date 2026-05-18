package com.foggy.navigator.claude.worker.controller.openapi;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class OpenApiAttachmentNormalizer {

    private static final List<String> ID_KEYS = List.of(
            "id",
            "attachmentId",
            "attachment_id",
            "attachmentRef",
            "attachment_ref",
            "ref"
    );
    private static final List<String> URL_KEYS = List.of(
            "url",
            "href",
            "downloadUrl",
            "download_url"
    );
    private static final List<String> NAME_KEYS = List.of(
            "name",
            "fileName",
            "filename"
    );
    private static final List<String> MIME_KEYS = List.of(
            "mimeType",
            "mime_type",
            "contentType",
            "content_type"
    );

    private OpenApiAttachmentNormalizer() {
    }

    static List<Map<String, Object>> normalize(
            Object metadataAttachments,
            List<Map<String, Object>> topLevelAttachments) {
        List<Map<String, Object>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        append(topLevelAttachments, result, seen, false);
        append(metadataAttachments, result, seen, true);

        return result;
    }

    private static void append(
            Object attachments,
            List<Map<String, Object>> result,
            Set<String> seen,
            boolean skipDuplicateKeys) {
        for (Object attachment : asItems(attachments)) {
            if (!(attachment instanceof Map<?, ?> map)) {
                continue;
            }
            Map<String, Object> normalized = copyStringKeyedMap(map);
            if (normalized.isEmpty()) {
                continue;
            }
            List<String> keys = identityKeys(normalized);
            if (keys.isEmpty()) {
                result.add(normalized);
                continue;
            }
            if (!containsAny(seen, keys)) {
                seen.addAll(keys);
                result.add(normalized);
            } else if (!skipDuplicateKeys) {
                replaceExisting(result, keys, normalized);
                seen.addAll(keys);
            }
        }
    }

    private static List<?> asItems(Object attachments) {
        if (attachments == null) {
            return List.of();
        }
        if (attachments instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        Class<?> type = attachments.getClass();
        if (!type.isArray()) {
            return List.of();
        }
        int length = Array.getLength(attachments);
        List<Object> result = new ArrayList<>(length);
        for (int i = 0; i < length; i++) {
            result.add(Array.get(attachments, i));
        }
        return result;
    }

    private static Map<String, Object> copyStringKeyedMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (key instanceof String text && !text.isBlank()) {
                result.put(text, value);
            }
        });
        return result;
    }

    private static List<String> identityKeys(Map<String, Object> attachment) {
        List<String> keys = new ArrayList<>();
        String id = firstText(attachment, ID_KEYS);
        if (id != null) {
            keys.add("id:" + id);
        }
        String url = firstText(attachment, URL_KEYS);
        if (url != null) {
            keys.add("url:" + url);
        }
        String name = firstText(attachment, NAME_KEYS);
        String mime = firstText(attachment, MIME_KEYS);
        if (name != null && mime != null) {
            keys.add("name_mime:" + name + "|" + mime);
        }
        return keys;
    }

    private static String firstText(Map<String, Object> attachment, List<String> keys) {
        for (String key : keys) {
            Object value = attachment.get(key);
            if (value instanceof String text && !text.isBlank()) {
                return text;
            }
        }
        return null;
    }

    private static boolean containsAny(Set<String> seen, List<String> keys) {
        for (String key : keys) {
            if (seen.contains(key)) {
                return true;
            }
        }
        return false;
    }

    private static void replaceExisting(
            List<Map<String, Object>> result,
            List<String> keys,
            Map<String, Object> replacement) {
        for (int i = 0; i < result.size(); i++) {
            if (overlaps(identityKeys(result.get(i)), keys)) {
                result.set(i, replacement);
                return;
            }
        }
        result.add(replacement);
    }

    private static boolean overlaps(List<String> left, List<String> right) {
        for (String key : left) {
            if (right.contains(key)) {
                return true;
            }
        }
        return false;
    }
}
