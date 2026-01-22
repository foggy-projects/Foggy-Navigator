package com.foggy.navigator.foundation.git;

import com.foggy.navigator.api.model.CreateConversationRequest;
import com.foggy.navigator.foundation.git.model.OpenHandsConversationResponse;
import com.foggy.navigator.foundation.git.model.OpenHandsEvent;
import com.foggy.navigator.foundation.git.model.OpenHandsMessageResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
public class OpenHandsClient {

    private final RestTemplate restTemplate;
    private final String openHandsApiUrl;
    private final String openHandsApiKey;

    public OpenHandsClient(RestTemplate restTemplate, String openHandsApiUrl, String openHandsApiKey) {
        this.restTemplate = restTemplate;
        this.openHandsApiUrl = openHandsApiUrl;
        this.openHandsApiKey = openHandsApiKey;
    }

    private HttpHeaders createHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (openHandsApiKey != null && !openHandsApiKey.isEmpty()) {
            headers.set("Authorization", "Bearer " + openHandsApiKey);
        }
        return headers;
    }

    private String getApiUrl(String path) {
        return openHandsApiUrl + "/api/v1" + path;
    }

    public <T> T post(String path, Object request, Class<T> responseType) {
        try {
            HttpEntity<Object> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<T> response = restTemplate.postForEntity(getApiUrl(path), entity, responseType);

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                throw new RuntimeException("OpenHands API 调用失败: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("OpenHands API 调用异常: path={}", path, e);
            throw new RuntimeException("OpenHands API 调用异常", e);
        }
    }

    public <T> T get(String path, Class<T> responseType) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<T> response = restTemplate.exchange(
                    getApiUrl(path),
                    HttpMethod.GET,
                    entity,
                    responseType
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                throw new RuntimeException("OpenHands API 调用失败: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("OpenHands API 调用异常: path={}", path, e);
            throw new RuntimeException("OpenHands API 调用异常", e);
        }
    }

    public <T> T delete(String path, Class<T> responseType) {
        try {
            HttpEntity<Void> entity = new HttpEntity<>(createHeaders());
            ResponseEntity<T> response = restTemplate.exchange(
                    getApiUrl(path),
                    HttpMethod.DELETE,
                    entity,
                    responseType
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                return response.getBody();
            } else {
                throw new RuntimeException("OpenHands API 调用失败: " + response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("OpenHands API 调用异常: path={}", path, e);
            throw new RuntimeException("OpenHands API 调用异常", e);
        }
    }

    public OpenHandsConversationResponse createConversation(CreateConversationRequest request) {
        log.info("创建 OpenHands Conversation: userId={}, projectId={}", request.getUserId(), request.getProjectId());

        Map<String, Object> requestBody = Map.of(
                "user_id", request.getUserId(),
                "project_id", request.getProjectId(),
                "git_repo_url", request.getGitRepoUrl(),
                "branch_name", request.getBranchName(),
                "initial_message", request.getInitialMessage() != null ? request.getInitialMessage() : ""
        );

        return post("/app-conversations/stream-start", requestBody, OpenHandsConversationResponse.class);
    }

    public OpenHandsConversationResponse getConversation(String conversationId) {
        log.info("获取 OpenHands Conversation: conversationId={}", conversationId);
        return get("/app-conversations/" + conversationId, OpenHandsConversationResponse.class);
    }

    public void deleteConversation(String conversationId) {
        log.info("删除 OpenHands Conversation: conversationId={}", conversationId);
        delete("/app-conversations/" + conversationId, Void.class);
    }

    public void stopConversation(String conversationId) {
        log.info("停止 OpenHands Conversation: conversationId={}", conversationId);
        post("/app-conversations/" + conversationId + "/stop", Map.of(), Void.class);
    }

    public OpenHandsMessageResponse sendMessage(String conversationId, String content) {
        log.info("发送消息: conversationId={}, content={}", conversationId, content);

        Map<String, Object> requestBody = Map.of(
                "content", content
        );

        return post("/app-conversations/" + conversationId + "/messages", requestBody, OpenHandsMessageResponse.class);
    }

    public List<OpenHandsEvent> searchEvents(String conversationId, String kind, String timestampGte, String timestampLt, String pageId, int limit) {
        log.info("搜索事件: conversationId={}, kind={}, limit={}", conversationId, kind, limit);

        StringBuilder path = new StringBuilder("/app-conversations/" + conversationId + "/events");
        path.append("?limit=").append(limit);

        if (kind != null && !kind.isEmpty()) {
            path.append("&kind=").append(kind);
        }
        if (timestampGte != null && !timestampGte.isEmpty()) {
            path.append("&timestamp_gte=").append(timestampGte);
        }
        if (timestampLt != null && !timestampLt.isEmpty()) {
            path.append("&timestamp_lt=").append(timestampLt);
        }
        if (pageId != null && !pageId.isEmpty()) {
            path.append("&page_id=").append(pageId);
        }

        return get(path.toString(), List.class);
    }

    public List<OpenHandsEvent> getNewEvents(String conversationId, String lastEventId) {
        log.info("获取新事件: conversationId={}, lastEventId={}", conversationId, lastEventId);

        String path = "/app-conversations/" + conversationId + "/events/new?last_event_id=" + lastEventId;

        return get(path, List.class);
    }
}
