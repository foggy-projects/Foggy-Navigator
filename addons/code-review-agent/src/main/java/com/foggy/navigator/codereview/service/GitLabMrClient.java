package com.foggy.navigator.codereview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.codereview.model.dto.DiffRefs;
import com.foggy.navigator.spi.config.GitProviderManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * GitLab MR API 客户端
 * <p>
 * 封装 MR diff 获取、评论发布、行级 inline discussion 等操作。
 * 复用 GitProviderManager 获取 token 和 baseUrl。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GitLabMrClient {

    private final GitProviderManager gitProviderManager;
    private final ObjectMapper objectMapper;
    private final RestTemplate codeReviewRestTemplate;

    /**
     * 解析凭证并缓存，避免每次 API 调用重复解密
     */
    public ResolvedCredentials resolveCredentials(String gitProviderConfigId) {
        String baseUrl = gitProviderManager.getGitProvider(gitProviderConfigId)
                .orElseThrow(() -> new IllegalArgumentException("Git provider config not found: " + gitProviderConfigId))
                .getBaseUrl();
        String token = gitProviderManager.getDecryptedToken(gitProviderConfigId);
        return new ResolvedCredentials(baseUrl, token);
    }

    /**
     * 获取 MR 变更（diff + diff_refs）
     *
     * @return JsonNode 包含 changes[], diff_refs{base_sha, start_sha, head_sha}, title 等
     */
    public JsonNode getMrChanges(ResolvedCredentials creds, long projectId, long mrIid) {
        String url = creds.baseUrl + "/api/v4/projects/" + projectId + "/merge_requests/" + mrIid + "/changes";

        HttpHeaders headers = createHeaders(creds.token);
        ResponseEntity<String> response = codeReviewRestTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        try {
            return objectMapper.readTree(response.getBody());
        } catch (Exception e) {
            throw new RuntimeException("Failed to parse MR changes response", e);
        }
    }

    /**
     * 从 MR changes 响应中提取 DiffRefs
     */
    public DiffRefs extractDiffRefs(JsonNode mrChanges) {
        JsonNode refs = mrChanges.path("diff_refs");
        return new DiffRefs(
                refs.path("base_sha").asText(null),
                refs.path("start_sha").asText(null),
                refs.path("head_sha").asText(null)
        );
    }

    /**
     * 在 MR 上发布总结评论（note）
     */
    public void postMrNote(ResolvedCredentials creds, long projectId, long mrIid, String body) {
        String url = creds.baseUrl + "/api/v4/projects/" + projectId + "/merge_requests/" + mrIid + "/notes";

        HttpHeaders headers = createHeaders(creds.token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, String> requestBody = Map.of("body", body);
        codeReviewRestTemplate.exchange(url, HttpMethod.POST,
                new HttpEntity<>(requestBody, headers), String.class);

        log.info("Posted summary note on project={} MR={}", projectId, mrIid);
    }

    /**
     * 在 MR 上发布行级 inline discussion
     *
     * @param filePath 文件路径（new_path）
     * @param newLine  新文件中的行号
     * @param body     评论内容
     * @param diffRefs diff SHAs（base/start/head）
     */
    public void postMrDiscussion(ResolvedCredentials creds, long projectId, long mrIid,
                                  String filePath, int newLine, String body, DiffRefs diffRefs) {
        String url = creds.baseUrl + "/api/v4/projects/" + projectId + "/merge_requests/" + mrIid + "/discussions";

        HttpHeaders headers = createHeaders(creds.token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        Map<String, Object> position = new LinkedHashMap<>();
        position.put("base_sha", diffRefs.getBaseSha());
        position.put("start_sha", diffRefs.getStartSha());
        position.put("head_sha", diffRefs.getHeadSha());
        position.put("position_type", "text");
        position.put("new_path", filePath);
        position.put("new_line", newLine);

        Map<String, Object> requestBody = new LinkedHashMap<>();
        requestBody.put("body", body);
        requestBody.put("position", position);

        try {
            codeReviewRestTemplate.exchange(url, HttpMethod.POST,
                    new HttpEntity<>(requestBody, headers), String.class);
            log.debug("Posted inline discussion on {}:{}", filePath, newLine);
        } catch (Exception e) {
            log.warn("Failed to post inline discussion on {}:{} - {}", filePath, newLine, e.getMessage());
            throw e;
        }
    }

    private HttpHeaders createHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("PRIVATE-TOKEN", token);
        return headers;
    }

    /**
     * 预解析的 GitLab 凭证，在一次审核流程中复用
     */
    public record ResolvedCredentials(String baseUrl, String token) {}
}
