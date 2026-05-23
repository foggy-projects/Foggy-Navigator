package com.foggy.navigator.observer.bff;

import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriUtils;

import java.io.IOException;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class LocalAttachmentStorageService {

    private final ObserverBffProperties properties;

    public LocalAttachmentStorageService(ObserverBffProperties properties) {
        this.properties = properties;
    }

    public AttachmentDescriptor store(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "file is required");
        }

        String id = UUID.randomUUID().toString();
        String fileName = safeFileName(file.getOriginalFilename());
        Path directory = properties.attachmentStorageDir().resolve(id).normalize();
        Path target = directory.resolve(fileName).normalize();
        if (!target.startsWith(directory)) {
            throw new ResponseStatusException(BAD_REQUEST, "invalid file name");
        }

        try {
            Files.createDirectories(directory);
            file.transferTo(target);
        } catch (IOException e) {
            throw new ResponseStatusException(BAD_REQUEST, "failed to store attachment", e);
        }

        String mimeType = file.getContentType();
        if (ObserverBffProperties.isBlank(mimeType)) {
            mimeType = probeContentType(target);
        }
        String kind = inferKind(fileName, mimeType);
        String encodedName = UriUtils.encodePathSegment(fileName, StandardCharsets.UTF_8);
        String url = properties.publicBaseUrl() + "/api/v1/observer/attachments/" + id + "/" + encodedName;
        String thumbnailUrl = kind.equals("image") ? url : null;

        return new AttachmentDescriptor(
                id,
                fileName,
                mimeType,
                file.getSize(),
                kind,
                url,
                thumbnailUrl,
                "navigator-observer-bff",
                Map.of(
                        "source", "navigator-chat-observer-bff",
                        "storage", target.toString()
                ));
    }

    public Resource load(String id, String fileName) {
        String safeName = safeFileName(fileName);
        Path directory = properties.attachmentStorageDir().resolve(id).normalize();
        Path target = directory.resolve(safeName).normalize();
        if (!target.startsWith(directory) || !Files.isRegularFile(target)) {
            throw new ResponseStatusException(NOT_FOUND, "attachment not found");
        }
        try {
            return new UrlResource(target.toUri());
        } catch (MalformedURLException e) {
            throw new ResponseStatusException(NOT_FOUND, "attachment not found", e);
        }
    }

    public MediaType mediaType(String id, String fileName) {
        Path target = properties.attachmentStorageDir().resolve(id).resolve(safeFileName(fileName)).normalize();
        String contentType = probeContentType(target);
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception ignored) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }

    private String safeFileName(String originalName) {
        String fileName = StringUtils.cleanPath(originalName == null ? "attachment" : originalName);
        int slashIndex = Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\'));
        if (slashIndex >= 0) {
            fileName = fileName.substring(slashIndex + 1);
        }
        if (fileName.isBlank() || fileName.equals(".") || fileName.equals("..")) {
            return "attachment";
        }
        return fileName;
    }

    private String probeContentType(Path target) {
        try {
            String contentType = Files.probeContentType(target);
            return ObserverBffProperties.isBlank(contentType) ? "application/octet-stream" : contentType;
        } catch (IOException ignored) {
            return "application/octet-stream";
        }
    }

    private String inferKind(String fileName, String mimeType) {
        String lowerName = fileName.toLowerCase(Locale.ROOT);
        String lowerMime = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (lowerMime.startsWith("image/")) return "image";
        if (lowerMime.equals("application/pdf") || lowerName.endsWith(".pdf")) return "pdf";
        if (lowerMime.startsWith("text/") || lowerName.endsWith(".txt") || lowerName.endsWith(".md")) return "text";
        if (lowerName.endsWith(".xls") || lowerName.endsWith(".xlsx") || lowerName.endsWith(".csv")) return "spreadsheet";
        if (lowerName.endsWith(".doc") || lowerName.endsWith(".docx")) return "document";
        if (lowerName.endsWith(".zip") || lowerName.endsWith(".rar") || lowerName.endsWith(".7z")) return "archive";
        return "file";
    }
}
