package com.foggy.navigator.observer.bff;

import java.util.Map;

public record AttachmentDescriptor(
        String id,
        String name,
        String mimeType,
        long size,
        String kind,
        String url,
        String thumbnailUrl,
        String provider,
        Map<String, Object> metadata) {
}
