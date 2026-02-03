package com.foggy.navigator.tutor.tool;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.tutor.config.TutorAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Coding Agent 工具执行器
 * 封装对 coding-agent 服务的 HTTP 调用
 */
@Slf4j
@Component
public class CodingAgentToolExecutor {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final TutorAgentProperties properties;

    public CodingAgentToolExecutor(TutorAgentProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
            .baseUrl(properties.getCodingAgentBaseUrl())
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .build();
    }

    /**
     * 列出 Git 凭证
     */
    public List<Map<String, Object>> listGitCredentials(String authToken) {
        log.info("Listing git credentials");
        try {
            String response = restClient.get()
                .uri("/api/v1/git-credentials")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .retrieve()
                .body(String.class);
            return extractDataAsList(response);
        } catch (Exception e) {
            log.error("Failed to list git credentials", e);
            throw new RuntimeException("Failed to list git credentials: " + e.getMessage(), e);
        }
    }

    /**
     * 列出 Git 项目
     */
    public List<Map<String, Object>> listGitProjects(String credentialId, String authToken) {
        log.info("Listing git projects for credential: {}", credentialId);
        try {
            String response = restClient.get()
                .uri("/api/v1/git/{credentialId}/projects", credentialId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .retrieve()
                .body(String.class);
            return extractDataAsList(response);
        } catch (Exception e) {
            log.error("Failed to list git projects", e);
            throw new RuntimeException("Failed to list git projects: " + e.getMessage(), e);
        }
    }

    /**
     * 列出 Git 分支
     */
    public List<Map<String, Object>> listGitBranches(String credentialId, String projectId, String authToken) {
        log.info("Listing git branches for project: {}", projectId);
        try {
            String response = restClient.get()
                .uri("/api/v1/git/{credentialId}/projects/{projectId}/branches", credentialId, projectId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .retrieve()
                .body(String.class);
            return extractDataAsList(response);
        } catch (Exception e) {
            log.error("Failed to list git branches", e);
            throw new RuntimeException("Failed to list git branches: " + e.getMessage(), e);
        }
    }

    /**
     * 创建编码会话
     */
    public Map<String, Object> createCodingConversation(Map<String, Object> request, String authToken) {
        log.info("Creating coding conversation");
        try {
            String response = restClient.post()
                .uri("/api/v1/conversations")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .body(request)
                .retrieve()
                .body(String.class);
            return extractDataAsMap(response);
        } catch (Exception e) {
            log.error("Failed to create coding conversation", e);
            throw new RuntimeException("Failed to create coding conversation: " + e.getMessage(), e);
        }
    }

    /**
     * 发送编码消息
     */
    public Map<String, Object> sendCodingMessage(String conversationId, Map<String, Object> request, String authToken) {
        log.info("Sending message to conversation: {}", conversationId);
        try {
            String response = restClient.post()
                .uri("/api/v1/conversations/{id}/messages", conversationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .body(request)
                .retrieve()
                .body(String.class);
            return extractDataAsMap(response);
        } catch (Exception e) {
            log.error("Failed to send coding message", e);
            throw new RuntimeException("Failed to send coding message: " + e.getMessage(), e);
        }
    }

    /**
     * 获取会话状态
     */
    public Map<String, Object> getConversationStatus(String conversationId, String authToken) {
        log.info("Getting conversation status: {}", conversationId);
        try {
            String response = restClient.get()
                .uri("/api/v1/conversations/{id}", conversationId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken)
                .retrieve()
                .body(String.class);
            return extractDataAsMap(response);
        } catch (Exception e) {
            log.error("Failed to get conversation status", e);
            throw new RuntimeException("Failed to get conversation status: " + e.getMessage(), e);
        }
    }

    // ===== Helper Methods =====

    private List<Map<String, Object>> extractDataAsList(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isArray()) {
                return objectMapper.convertValue(dataNode, new TypeReference<List<Map<String, Object>>>() {});
            }
            return List.of();
        } catch (Exception e) {
            log.warn("Failed to parse response as list: {}", e.getMessage());
            return List.of();
        }
    }

    private Map<String, Object> extractDataAsMap(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode dataNode = root.get("data");
            if (dataNode != null && dataNode.isObject()) {
                return objectMapper.convertValue(dataNode, new TypeReference<Map<String, Object>>() {});
            }
            return Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse response as map: {}", e.getMessage());
            return Map.of();
        }
    }
}
