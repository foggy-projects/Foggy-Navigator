package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.AccountContextFileDTO;
import com.foggy.navigator.business.agent.model.dto.AccountContextFileTreeDTO;
import com.foggy.navigator.business.agent.model.form.AccountContextFileWriteForm;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class AccountContextFileService {

    public static final String ACCOUNT_POLICY = "ACCOUNT_POLICY.md";
    public static final String AGENT = "AGENT.md";
    public static final String MEMORY = "MEMORY.md";

    private static final int MAX_WRITE_BYTES = 64 * 1024;
    private static final Pattern SENSITIVE_ASSIGNMENT = Pattern.compile(
            "(?i)(task_scoped_token|adapterConfigJson|manifestJson|client_app_secret|navi_client_app_secret|x-client-app-secret)\\s*[:=]\\s*\\S+");

    private final ClientAppUserGrantService userGrantService;
    private final ObjectMapper objectMapper;

    @Value("${foggy.navigator.business.agent.dev-sync-worker-url:http://localhost:3061}")
    private String devSyncWorkerUrl;

    @Value("${foggy.navigator.business.agent.dev-sync-worker-token:}")
    private String devSyncWorkerToken;

    public AccountContextFileTreeDTO list(
            String tenantId,
            String clientAppId,
            String upstreamUserId) {
        checkAccess(tenantId, clientAppId, upstreamUserId);
        return workerRequest(
                "GET",
                "/api/v1/account-context/accounts/" + encode(upstreamUserId) + "/files",
                null,
                AccountContextFileTreeDTO.class);
    }

    public AccountContextFileDTO read(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String fileName) {
        checkAccess(tenantId, clientAppId, upstreamUserId);
        String normalized = normalizeFileName(fileName);
        return workerRequest(
                "GET",
                "/api/v1/account-context/accounts/" + encode(upstreamUserId) + "/files/" + encode(normalized),
                null,
                AccountContextFileDTO.class);
    }

    public AccountContextFileDTO writePolicy(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String fileName,
            AccountContextFileWriteForm form) {
        checkAccess(tenantId, clientAppId, upstreamUserId);
        String normalized = normalizeFileName(fileName);
        if (!ACCOUNT_POLICY.equals(normalized)) {
            throw new IllegalArgumentException("Only ACCOUNT_POLICY.md is writable through OpenAPI");
        }
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String content = form.getContent();
        if (content == null) {
            throw new IllegalArgumentException("content is required");
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_WRITE_BYTES) {
            throw new IllegalArgumentException("ACCOUNT_POLICY.md exceeds " + MAX_WRITE_BYTES + " bytes");
        }
        rejectSensitiveContent(content);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("content", content);
        if (StringUtils.hasText(form.getExpectedSha256())) {
            body.put("expected_sha256", form.getExpectedSha256());
        }
        return workerRequest(
                "PUT",
                "/api/v1/account-context/accounts/" + encode(upstreamUserId) + "/files/" + encode(normalized),
                body,
                AccountContextFileDTO.class);
    }

    private void checkAccess(String tenantId, String clientAppId, String upstreamUserId) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(clientAppId, "clientAppId is required");
        Assert.hasText(upstreamUserId, "upstreamUserId is required");
        userGrantService.checkUpstreamUserAccess(tenantId, clientAppId, upstreamUserId);
    }

    private <T> T workerRequest(String method, String path, Object body, Class<T> responseType) {
        if (!StringUtils.hasText(devSyncWorkerUrl)) {
            throw new IllegalStateException("dev-sync-worker-url is not configured");
        }
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(devSyncWorkerUrl + path))
                    .version(HttpClient.Version.HTTP_1_1)
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .header("Content-Type", "application/json");
            if (StringUtils.hasText(devSyncWorkerToken)) {
                requestBuilder.header("Authorization", "Bearer " + devSyncWorkerToken);
            }
            if (body == null) {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)));
            }

            HttpResponse<String> response = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build()
                    .send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("Worker account context request failed: HTTP " + response.statusCode()
                        + " " + response.body());
            }
            return objectMapper.readValue(response.body(), responseType);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Worker account context request interrupted", e);
        } catch (Exception e) {
            if (e instanceof IllegalStateException) {
                throw (IllegalStateException) e;
            }
            throw new IllegalStateException("Worker account context request failed", e);
        }
    }

    private String normalizeFileName(String fileName) {
        Assert.hasText(fileName, "fileName is required");
        String value = fileName.trim();
        if (ACCOUNT_POLICY.equals(value) || AGENT.equals(value) || MEMORY.equals(value)) {
            return value;
        }
        throw new IllegalArgumentException("unsupported account context file");
    }

    private void rejectSensitiveContent(String content) {
        String lower = content.toLowerCase(Locale.ROOT);
        if (lower.contains("authorization: bearer ")
                || lower.contains("-----begin ")
                || lower.contains("sk-")
                || SENSITIVE_ASSIGNMENT.matcher(content).find()) {
            throw new IllegalArgumentException("ACCOUNT_POLICY.md must not contain sensitive runtime values");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
