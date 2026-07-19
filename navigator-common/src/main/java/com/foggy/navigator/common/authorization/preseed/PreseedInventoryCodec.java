package com.foggy.navigator.common.authorization.preseed;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Package-local JSON boundary for the hermetic offline validator. */
final class PreseedInventoryCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);

    DecodedDocument decode(String document) {
        if (document == null || document.isBlank()) {
            return DecodedDocument.failure(PreseedInventoryReasonCode.PRESEED_DOCUMENT_MALFORMED);
        }
        try {
            JsonNode root = MAPPER.readTree(document);
            if (root == null || root.isNull()) {
                return DecodedDocument.failure(PreseedInventoryReasonCode.PRESEED_DOCUMENT_MALFORMED);
            }
            return DecodedDocument.success(root);
        } catch (JsonProcessingException exception) {
            return DecodedDocument.failure(PreseedInventoryReasonCode.PRESEED_DOCUMENT_MALFORMED);
        }
    }

    record DecodedDocument(JsonNode document, PreseedInventoryReasonCode failureReason) {

        static DecodedDocument success(JsonNode document) {
            return new DecodedDocument(document, null);
        }

        static DecodedDocument failure(PreseedInventoryReasonCode failureReason) {
            return new DecodedDocument(null, failureReason);
        }

        boolean successful() {
            return document != null;
        }
    }
}
