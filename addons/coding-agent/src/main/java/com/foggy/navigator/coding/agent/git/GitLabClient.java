package com.foggy.navigator.coding.agent.git;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.coding.agent.api.model.GitBranch;
import com.foggy.navigator.coding.agent.api.model.GitProject;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
public class GitLabClient implements GitProviderService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GitLabClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<GitProject> listProjects(GitCredentialEntity credential, String search, int page, int perPage) {
        validateProvider(credential);

        String url = UriComponentsBuilder.fromHttpUrl(credential.getServerUrl())
                .path("/api/v4/projects")
                .queryParam("membership", true)
                .queryParam("page", page)
                .queryParam("per_page", perPage)
                .queryParam("order_by", "last_activity_at")
                .queryParamIfPresent("search", search != null && !search.isEmpty() ? java.util.Optional.of(search) : java.util.Optional.empty())
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return parseProjects(response.getBody());
        } catch (Exception e) {
            log.error("GitLab 获取项目列表失败: {}", e.getMessage());
            throw new RuntimeException("获取 GitLab 项目列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public GitProject getProject(GitCredentialEntity credential, String projectId) {
        validateProvider(credential);

        String url = UriComponentsBuilder.fromHttpUrl(credential.getServerUrl())
                .path("/api/v4/projects/{id}")
                .buildAndExpand(projectId)
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return parseProject(node);
        } catch (Exception e) {
            log.error("GitLab 获取项目详情失败: projectId={}, error={}", projectId, e.getMessage());
            throw new RuntimeException("获取 GitLab 项目详情失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<GitBranch> listBranches(GitCredentialEntity credential, String projectId, String search) {
        validateProvider(credential);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(credential.getServerUrl())
                .path("/api/v4/projects/{id}/repository/branches")
                .queryParam("per_page", 100);

        if (search != null && !search.isEmpty()) {
            builder.queryParam("search", search);
        }

        String url = builder.buildAndExpand(projectId).toUriString();
        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return parseBranches(response.getBody());
        } catch (Exception e) {
            log.error("GitLab 获取分支列表失败: projectId={}, error={}", projectId, e.getMessage());
            throw new RuntimeException("获取 GitLab 分支列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(GitCredentialEntity credential) {
        validateProvider(credential);

        String url = UriComponentsBuilder.fromHttpUrl(credential.getServerUrl())
                .path("/api/v4/user")
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("GitLab 连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    private void validateProvider(GitCredentialEntity credential) {
        if (credential.getProvider() != GitProvider.GITLAB) {
            throw new IllegalArgumentException("GitLabClient 只支持 GITLAB 类型凭证");
        }
    }

    private HttpHeaders createHeaders(GitCredentialEntity credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("PRIVATE-TOKEN", credential.getAccessToken());
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private List<GitProject> parseProjects(String json) {
        List<GitProject> projects = new ArrayList<>();
        try {
            JsonNode nodes = objectMapper.readTree(json);
            for (JsonNode node : nodes) {
                projects.add(parseProject(node));
            }
        } catch (Exception e) {
            log.error("解析 GitLab 项目列表失败", e);
        }
        return projects;
    }

    private GitProject parseProject(JsonNode node) {
        return GitProject.builder()
                .id(node.path("id").asText())
                .name(node.path("name").asText())
                .path(node.path("path").asText())
                .pathWithNamespace(node.path("path_with_namespace").asText())
                .description(node.path("description").asText(null))
                .httpUrlToRepo(node.path("http_url_to_repo").asText())
                .sshUrlToRepo(node.path("ssh_url_to_repo").asText())
                .defaultBranch(node.path("default_branch").asText("main"))
                .avatarUrl(node.path("avatar_url").asText(null))
                .webUrl(node.path("web_url").asText())
                .build();
    }

    private List<GitBranch> parseBranches(String json) {
        List<GitBranch> branches = new ArrayList<>();
        try {
            JsonNode nodes = objectMapper.readTree(json);
            for (JsonNode node : nodes) {
                JsonNode commit = node.path("commit");
                branches.add(GitBranch.builder()
                        .name(node.path("name").asText())
                        .isDefault(node.path("default").asBoolean(false))
                        .isProtected(node.path("protected").asBoolean(false))
                        .commitId(commit.path("id").asText())
                        .commitMessage(commit.path("message").asText())
                        .build());
            }
        } catch (Exception e) {
            log.error("解析 GitLab 分支列表失败", e);
        }
        return branches;
    }
}
