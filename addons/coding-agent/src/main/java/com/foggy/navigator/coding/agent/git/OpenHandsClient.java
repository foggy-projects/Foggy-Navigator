package com.foggy.navigator.coding.agent.git;

import com.foggy.navigator.coding.agent.api.model.CreateConversationRequest;
import com.foggy.navigator.coding.agent.git.model.OpenHandsConversationResponse;
import com.foggy.navigator.coding.agent.git.model.OpenHandsEvent;
import com.foggy.navigator.coding.agent.git.model.OpenHandsMessageResponse;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationInfo;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartRequest;
import com.foggy.navigator.coding.agent.git.model.v1.AppConversationStartTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

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

    public <T> T postRaw(String path, Object request, Class<T> responseType) {
        try {
            String url = openHandsApiUrl + path;
            log.debug("POST Raw: {}", url);
            HttpEntity<Object> entity = new HttpEntity<>(request, createHeaders());
            ResponseEntity<T> response = restTemplate.postForEntity(url, entity, responseType);

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

    // ===== V1 API Methods =====

    public AppConversationStartTask startConversation(AppConversationStartRequest req) {
        log.info("启动 OH V1 会话: repo={}, branch={}", req.getSelectedRepository(), req.getSelectedBranch());
        return post("/app-conversations", req, AppConversationStartTask.class);
    }

    public AppConversationStartTask getStartTask(String taskId) {
        log.debug("查询 OH V1 启动任务: taskId={}", taskId);
        List result = get("/app-conversations/start-tasks?ids=" + taskId, List.class);
        if (result != null && !result.isEmpty()) {
            Object first = result.get(0);
            if (first instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) first;
                return AppConversationStartTask.builder()
                        .id((String) map.get("id"))
                        .appConversationId((String) map.get("app_conversation_id"))
                        .sandboxId((String) map.get("sandbox_id"))
                        .agentServerUrl((String) map.get("agent_server_url"))
                        .status((String) map.get("status"))
                        .detail((String) map.get("detail"))
                        .build();
            }
        }
        return null;
    }

    public AppConversationInfo getConversationInfo(String ohConversationId) {
        log.info("获取 OH V1 会话信息: ohConversationId={}", ohConversationId);
        // V1 API: GET /api/v1/app-conversations?ids=[id]
        List result = get("/app-conversations?ids=" + ohConversationId, List.class);
        if (result != null && !result.isEmpty()) {
            Object first = result.get(0);
            if (first instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) first;
                return AppConversationInfo.builder()
                        .id((String) map.get("id"))
                        .sandboxId((String) map.get("sandbox_id"))
                        .sandboxStatus((String) map.get("sandbox_status"))
                        .title((String) map.get("title"))
                        .selectedRepository((String) map.get("selected_repository"))
                        .selectedBranch((String) map.get("selected_branch"))
                        .executionStatus((String) map.get("execution_status"))
                        .conversationUrl((String) map.get("conversation_url"))
                        .sessionApiKey((String) map.get("session_api_key"))
                        .createdAt((String) map.get("created_at"))
                        .updatedAt((String) map.get("updated_at"))
                        .build();
            }
        }
        return null;
    }

    public void deleteSandbox(String sandboxId) {
        log.info("删除 OH V1 sandbox: sandboxId={}", sandboxId);
        delete("/sandboxes/" + sandboxId, Void.class);
    }

    public void pauseSandbox(String sandboxId) {
        log.info("暂停 OH V1 sandbox: sandboxId={}", sandboxId);
        post("/sandboxes/" + sandboxId + "/pause", Map.of(), Void.class);
    }

    public void resumeSandbox(String sandboxId) {
        log.info("恢复 OH V1 sandbox: sandboxId={}", sandboxId);
        post("/sandboxes/" + sandboxId + "/resume", Map.of(), Void.class);
    }

    // ===== Legacy methods (kept for compatibility during transition) =====

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
        log.info("发送消息到 OH V1: conversationId={}, contentLength={}", conversationId, content.length());

        // OH V1: Messages go to the agent server via POST /api/conversations/{id}/events
        // Step 1: Get conversation_url and session_api_key from the main server
        AppConversationInfo info = getConversationInfo(conversationId);
        if (info == null || info.getConversationUrl() == null) {
            throw new RuntimeException("无法获取 OH 会话的 conversation_url: " + conversationId);
        }

        // conversation_url = http://host:port/api/conversations/{id}
        // events endpoint  = http://host:port/api/conversations/{id}/events
        String eventsUrl = info.getConversationUrl() + "/events";
        log.info("发送消息到 agent server: url={}", eventsUrl);

        // Step 2: Build headers with X-Session-API-Key (agent server auth)
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (info.getSessionApiKey() != null) {
            headers.set("X-Session-API-Key", info.getSessionApiKey());
        }

        // Step 3: Build SendMessageRequest body
        // {"role": "user", "content": [{"type": "text", "text": "..."}], "run": true}
        Map<String, Object> textContent = Map.of(
                "type", "text",
                "text", content
        );
        Map<String, Object> requestBody = Map.of(
                "role", "user",
                "content", List.of(textContent),
                "run", true  // auto-start the agent loop
        );

        HttpEntity<Object> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    eventsUrl, entity, Map.class);
            log.info("消息已发送到 agent server: conversationId={}, response={}", conversationId, response.getBody());
            return null; // agent server returns {"success": true}, not OpenHandsMessageResponse
        } catch (Exception e) {
            log.error("发送消息到 agent server 失败: url={}", eventsUrl, e);
            throw new RuntimeException("发送消息到 agent server 失败", e);
        }
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

    /**
     * Fetch events from the OH agent server.
     * Returns the raw response body map containing:
     *   "items": List of event maps
     *   "next_page_id": String page token for pagination (null if no more pages)
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> getNewEvents(String conversationId, String pageId) {
        log.debug("获取新事件: conversationId={}, pageId={}", conversationId, pageId);

        // OH V1: Events must be fetched from the agent server, not the main server.
        AppConversationInfo info = getConversationInfo(conversationId);
        if (info == null || info.getConversationUrl() == null) {
            log.warn("无法获取 conversation_url，跳过事件拉取: conversationId={}", conversationId);
            return Map.of("items", List.of());
        }

        // Agent server events endpoint: GET {conversation_url}/events/search
        String eventsUrl = info.getConversationUrl() + "/events/search?limit=50&sort_order=TIMESTAMP";
        if (pageId != null && !pageId.isEmpty()) {
            eventsUrl += "&page_id=" + pageId;
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (info.getSessionApiKey() != null) {
            headers.set("X-Session-API-Key", info.getSessionApiKey());
        }

        try {
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    eventsUrl, HttpMethod.GET, entity, Map.class);
            Map<String, Object> body = response.getBody();
            if (body != null && body.containsKey("items")) {
                List items = (List) body.get("items");
                log.debug("从 agent server 获取到 {} 个事件, nextPageId={}",
                        items.size(), body.get("next_page_id"));
                return body;
            }
            log.debug("agent server 响应中无 items 字段: keys={}", body != null ? body.keySet() : "null");
            return Map.of("items", List.of());
        } catch (Exception e) {
            log.error("从 agent server 获取事件失败: url={}, error={}", eventsUrl, e.getMessage());
            return Map.of("items", List.of());
        }
    }
}
