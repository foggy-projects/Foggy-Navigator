package com.foggy.navigator.coding.agent.api.controller;

import com.foggy.navigator.coding.agent.api.model.GitBranch;
import com.foggy.navigator.coding.agent.api.model.GitProject;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.repository.GitCredentialRepository;
import com.foggy.navigator.coding.agent.git.GitProviderFactory;
import com.foggy.navigator.coding.agent.git.GitProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/git")
@Slf4j
public class GitProviderController {

    @Autowired
    private GitCredentialRepository gitCredentialRepository;

    @Autowired
    private GitProviderFactory gitProviderFactory;

    @GetMapping("/{credentialId}/projects")
    public ResponseEntity<List<GitProject>> listProjects(
            @RequestParam String userId,
            @PathVariable String credentialId,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int perPage) {
        log.info("GET /api/v1/git/{}/projects - 获取项目列表: search={}, page={}, perPage={}",
                credentialId, search, page, perPage);

        GitCredentialEntity credential = getCredentialForUser(userId, credentialId);
        GitProviderService service = gitProviderFactory.getService(credential.getProvider());
        List<GitProject> projects = service.listProjects(credential, search, page, perPage);

        return ResponseEntity.ok(projects);
    }

    @GetMapping("/{credentialId}/projects/{projectId}")
    public ResponseEntity<GitProject> getProject(
            @RequestParam String userId,
            @PathVariable String credentialId,
            @PathVariable String projectId) {
        log.info("GET /api/v1/git/{}/projects/{} - 获取项目详情", credentialId, projectId);

        GitCredentialEntity credential = getCredentialForUser(userId, credentialId);
        GitProviderService service = gitProviderFactory.getService(credential.getProvider());
        GitProject project = service.getProject(credential, projectId);

        return ResponseEntity.ok(project);
    }

    @GetMapping("/{credentialId}/projects/{projectId}/branches")
    public ResponseEntity<List<GitBranch>> listBranches(
            @RequestParam String userId,
            @PathVariable String credentialId,
            @PathVariable String projectId,
            @RequestParam(required = false) String search) {
        log.info("GET /api/v1/git/{}/projects/{}/branches - 获取分支列表: search={}",
                credentialId, projectId, search);

        GitCredentialEntity credential = getCredentialForUser(userId, credentialId);
        GitProviderService service = gitProviderFactory.getService(credential.getProvider());
        List<GitBranch> branches = service.listBranches(credential, projectId, search);

        return ResponseEntity.ok(branches);
    }

    @PostMapping("/{credentialId}/test")
    public ResponseEntity<Map<String, Object>> testConnection(
            @RequestParam String userId,
            @PathVariable String credentialId) {
        log.info("POST /api/v1/git/{}/test - 测试连接", credentialId);

        GitCredentialEntity credential = getCredentialForUser(userId, credentialId);
        GitProviderService service = gitProviderFactory.getService(credential.getProvider());
        boolean success = service.testConnection(credential);

        return ResponseEntity.ok(Map.of(
                "credentialId", credentialId,
                "provider", credential.getProvider(),
                "serverUrl", credential.getServerUrl(),
                "success", success,
                "message", success ? "连接成功" : "连接失败"
        ));
    }

    private GitCredentialEntity getCredentialForUser(String userId, String credentialId) {
        GitCredentialEntity credential = gitCredentialRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> new RuntimeException("凭证不存在: " + credentialId));

        if (!credential.getUserId().equals(userId)) {
            throw new RuntimeException("无权访问该凭证");
        }

        return credential;
    }
}
