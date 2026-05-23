package com.foggy.navigator.claude.worker.controller.openapi;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiAttachmentNormalizerTest {

    @Test
    void topLevelAttachmentsAreCanonicalAndMetadataOnlyAttachmentsAreRetained() {
        Map<String, Object> legacyDuplicate = Map.of(
                "id", "att-1",
                "name", "legacy.png",
                "url", "https://tms.example.com/legacy.png");
        Map<String, Object> legacyOnly = Map.of(
                "id", "att-2",
                "name", "legacy-only.png",
                "url", "https://tms.example.com/legacy-only.png");
        Map<String, Object> canonical = Map.of(
                "id", "att-1",
                "name", "canonical.png",
                "url", "https://tms.example.com/canonical.png");

        List<Map<String, Object>> result = OpenApiAttachmentNormalizer.normalize(
                List.of(legacyDuplicate, legacyOnly),
                List.of(canonical));

        assertEquals(List.of(canonical, legacyOnly), result);
    }

    @Test
    void metadataArrayAttachmentsAreAccepted() {
        Map<String, Object> attachment = Map.of(
                "attachment_id", "att-1",
                "name", "array.png",
                "url", "https://tms.example.com/array.png");

        List<Map<String, Object>> result = OpenApiAttachmentNormalizer.normalize(
                new Object[]{attachment},
                null);

        assertEquals(List.of(attachment), result);
    }

    @Test
    void duplicateMetadataAttachmentCanMatchAnyCanonicalIdentityAlias() {
        Map<String, Object> legacyDuplicateByUrl = Map.of(
                "name", "legacy.png",
                "url", "https://tms.example.com/shared.png");
        Map<String, Object> canonical = Map.of(
                "id", "att-1",
                "name", "canonical.png",
                "url", "https://tms.example.com/shared.png");

        List<Map<String, Object>> result = OpenApiAttachmentNormalizer.normalize(
                List.of(legacyDuplicateByUrl),
                List.of(canonical));

        assertEquals(List.of(canonical), result);
    }

    @Test
    void invalidMetadataAttachmentsAreIgnored() {
        List<Map<String, Object>> result = OpenApiAttachmentNormalizer.normalize("not-a-list", null);

        assertTrue(result.isEmpty());
    }

    @Test
    void nullItemsInsideMetadataAttachmentListAreIgnored() {
        Map<String, Object> attachment = Map.of(
                "id", "att-1",
                "name", "valid.png",
                "url", "https://tms.example.com/valid.png");
        List<Object> legacyAttachments = new ArrayList<>();
        legacyAttachments.add(null);
        legacyAttachments.add(attachment);

        List<Map<String, Object>> result = OpenApiAttachmentNormalizer.normalize(legacyAttachments, null);

        assertEquals(List.of(attachment), result);
    }
}
