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
public class GitHubClient implements GitProviderService {

    private static final String GITHUB_API_URL = "https://api.github.com";

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    public GitHubClient(ObjectMapper objectMapper) {
        this.restTemplate = new RestTemplate();
        this.objectMapper = objectMapper;
    }

    @Override
    public List<GitProject> listProjects(GitCredentialEntity credential, String search, int page, int perPage) {
        validateProvider(credential);

        String baseUrl = getApiBaseUrl(credential);
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/user/repos")
                .queryParam("page", page)
                .queryParam("per_page", perPage)
                .queryParam("sort", "updated")
                .queryParam("direction", "desc")
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            List<GitProject> projects = parseProjects(response.getBody());

            // GitHub 不支持服务端搜索，客户端过滤
            if (search != null && !search.isEmpty()) {
                String lowerSearch = search.toLowerCase();
                projects = projects.stream()
                        .filter(p -> p.getName().toLowerCase().contains(lowerSearch)
                                || (p.getDescription() != null && p.getDescription().toLowerCase().contains(lowerSearch)))
                        .toList();
            }

            return projects;
        } catch (Exception e) {
            log.error("GitHub 获取仓库列表失败: {}", e.getMessage());
            throw new RuntimeException("获取 GitHub 仓库列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public GitProject getProject(GitCredentialEntity credential, String projectId) {
        validateProvider(credential);

        // GitHub projectId 格式: owner/repo
        String baseUrl = getApiBaseUrl(credential);
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/repos/{ownerRepo}")
                .buildAndExpand(projectId)
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            JsonNode node = objectMapper.readTree(response.getBody());
            return parseProject(node);
        } catch (Exception e) {
            log.error("GitHub 获取仓库详情失败: projectId={}, error={}", projectId, e.getMessage());
            throw new RuntimeException("获取 GitHub 仓库详情失败: " + e.getMessage(), e);
        }
    }

    @Override
    public List<GitBranch> listBranches(GitCredentialEntity credential, String projectId, String search) {
        validateProvider(credential);

        String baseUrl = getApiBaseUrl(credential);
        String url = UriComponentsBuilder.fromHttpUrl(baseUrl)
                .path("/repos/{ownerRepo}/branches")
                .queryParam("per_page", 100)
                .buildAndExpand(projectId)
                .toUriString();

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            List<GitBranch> branches = parseBranches(response.getBody());

            // 客户端过滤
            if (search != null && !search.isEmpty()) {
                String lowerSearch = search.toLowerCase();
                branches = branches.stream()
                        .filter(b -> b.getName().toLowerCase().contains(lowerSearch))
                        .toList();
            }

            return branches;
        } catch (Exception e) {
            log.error("GitHub 获取分支列表失败: projectId={}, error={}", projectId, e.getMessage());
            throw new RuntimeException("获取 GitHub 分支列表失败: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean testConnection(GitCredentialEntity credential) {
        validateProvider(credential);

        String baseUrl = getApiBaseUrl(credential);
        String url = baseUrl + "/user";

        HttpEntity<Void> entity = new HttpEntity<>(createHeaders(credential));

        try {
            ResponseEntity<String> response = restTemplate.exchange(url, HttpMethod.GET, entity, String.class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("GitHub 连接测试失败: {}", e.getMessage());
            return false;
        }
    }

    private void validateProvider(GitCredentialEntity credential) {
        if (credential.getProvider() != GitProvider.GITHUB) {
            throw new IllegalArgumentException("GitHubClient 只支持 GITHUB 类型凭证");
        }
    }

    private String getApiBaseUrl(GitCredentialEntity credential) {
        // 支持 GitHub Enterprise
        if (credential.getServerUrl() != null
                && !credential.getServerUrl().isEmpty()
                && !credential.getServerUrl().contains("github.com")) {
            return credential.getServerUrl() + "/api/v3";
        }
        return GITHUB_API_URL;
    }

    private HttpHeaders createHeaders(GitCredentialEntity credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Authorization", "Bearer " + credential.getAccessToken());
        headers.set("Accept", "application/vnd.github+json");
        headers.set("X-GitHub-Api-Version", "2022-11-28");
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
            log.error("解析 GitHub 仓库列表失败", e);
        }
        return projects;
    }

    private GitProject parseProject(JsonNode node) {
        return GitProject.builder()
                .id(node.path("full_name").asText())  // GitHub 用 full_name 作为 ID
                .name(node.path("name").asText())
                .path(node.path("name").asText())
                .pathWithNamespace(node.path("full_name").asText())
                .description(node.path("description").asText(null))
                .httpUrlToRepo(node.path("clone_url").asText())
                .sshUrlToRepo(node.path("ssh_url").asText())
                .defaultBranch(node.path("default_branch").asText("main"))
                .avatarUrl(node.path("owner").path("avatar_url").asText(null))
                .webUrl(node.path("html_url").asText())
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
                        .isDefault(false)  // GitHub branches API 不返回 default 标记
                        .isProtected(node.path("protected").asBoolean(false))
                        .commitId(commit.path("sha").asText())
                        .commitMessage(null)  // GitHub branches API 不返回 commit message
                        .build());
            }
        } catch (Exception e) {
            log.error("解析 GitHub 分支列表失败", e);
        }
        return branches;
    }
}
