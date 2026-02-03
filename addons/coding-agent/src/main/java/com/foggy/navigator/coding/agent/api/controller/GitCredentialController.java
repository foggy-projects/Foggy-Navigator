package com.foggy.navigator.coding.agent.api.controller;

import com.foggy.navigator.coding.agent.api.model.CreateGitCredentialRequest;
import com.foggy.navigator.coding.agent.api.model.GitCredentialResponse;
import com.foggy.navigator.coding.agent.api.model.UpdateGitCredentialRequest;
import com.foggy.navigator.coding.agent.api.model.entity.GitCredentialEntity;
import com.foggy.navigator.coding.agent.api.model.entity.GitProvider;
import com.foggy.navigator.coding.agent.api.repository.GitCredentialRepository;
import com.foggy.navigator.coding.agent.api.service.GitCredentialService;
import com.foggy.navigator.coding.agent.git.GitProviderFactory;
import com.foggy.navigator.coding.agent.git.GitProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/git-credentials")
@Slf4j
public class GitCredentialController {

    @Autowired
    private GitCredentialService gitCredentialService;

    @Autowired
    private GitCredentialRepository gitCredentialRepository;

    @Autowired
    private GitProviderFactory gitProviderFactory;

    @PostMapping
    public ResponseEntity<GitCredentialResponse> createCredential(
            @RequestParam String userId,
            @RequestBody CreateGitCredentialRequest request) {
        log.info("POST /api/v1/git-credentials - 创建凭证: userId={}, provider={}", userId, request.getProvider());
        GitCredentialResponse response = gitCredentialService.createCredential(userId, request);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<GitCredentialResponse>> listCredentials(
            @RequestParam String userId,
            @RequestParam(required = false) GitProvider provider) {
        log.info("GET /api/v1/git-credentials - 查询凭证列表: userId={}, provider={}", userId, provider);

        List<GitCredentialResponse> credentials;
        if (provider != null) {
            credentials = gitCredentialService.listCredentialsByProvider(userId, provider);
        } else {
            credentials = gitCredentialService.listCredentials(userId);
        }
        return ResponseEntity.ok(credentials);
    }

    @GetMapping("/{credentialId}")
    public ResponseEntity<GitCredentialResponse> getCredential(
            @RequestParam String userId,
            @PathVariable String credentialId) {
        log.info("GET /api/v1/git-credentials/{} - 获取凭证", credentialId);
        GitCredentialResponse response = gitCredentialService.getCredential(userId, credentialId);
        return ResponseEntity.ok(response);
    }

    @PutMapping("/{credentialId}")
    public ResponseEntity<GitCredentialResponse> updateCredential(
            @RequestParam String userId,
            @PathVariable String credentialId,
            @RequestBody UpdateGitCredentialRequest request) {
        log.info("PUT /api/v1/git-credentials/{} - 更新凭证", credentialId);
        GitCredentialResponse response = gitCredentialService.updateCredential(userId, credentialId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{credentialId}")
    public ResponseEntity<Void> deleteCredential(
            @RequestParam String userId,
            @PathVariable String credentialId) {
        log.info("DELETE /api/v1/git-credentials/{} - 删除凭证", credentialId);
        gitCredentialService.deleteCredential(userId, credentialId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{credentialId}/test")
    public ResponseEntity<Map<String, Object>> testCredential(
            @RequestParam String userId,
            @PathVariable String credentialId) {
        log.info("POST /api/v1/git-credentials/{}/test - 测试凭证", credentialId);

        GitCredentialEntity credential = gitCredentialRepository.findByCredentialId(credentialId)
                .orElseThrow(() -> new RuntimeException("凭证不存在: " + credentialId));

        if (!credential.getUserId().equals(userId)) {
            throw new RuntimeException("无权访问该凭证");
        }

        GitProviderService service = gitProviderFactory.getService(credential.getProvider());
        boolean success = service.testConnection(credential);

        return ResponseEntity.ok(Map.of(
                "credentialId", credentialId,
                "provider", credential.getProvider(),
                "success", success,
                "message", success ? "连接成功" : "连接失败"
        ));
    }
}
