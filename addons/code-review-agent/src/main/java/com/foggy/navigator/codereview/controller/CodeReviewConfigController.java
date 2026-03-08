package com.foggy.navigator.codereview.controller;

import com.foggy.navigator.codereview.model.dto.CodeReviewConfigDTO;
import com.foggy.navigator.codereview.model.dto.CodeReviewRecordDTO;
import com.foggy.navigator.codereview.model.entity.CodeReviewConfigEntity;
import com.foggy.navigator.codereview.model.entity.CodeReviewRecordEntity;
import com.foggy.navigator.codereview.model.form.CodeReviewConfigForm;
import com.foggy.navigator.codereview.repository.CodeReviewConfigRepository;
import com.foggy.navigator.codereview.repository.CodeReviewRecordRepository;
import com.foggy.navigator.codereview.service.CodeReviewService;
import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.security.CredentialEncryptor;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * 代码审核配置管理 API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/code-review")
@RequireAuth
@RequiredArgsConstructor
public class CodeReviewConfigController {

    /** Webhook 端点路径常量，与 WebhookController 保持同步 */
    static final String WEBHOOK_PATH = "/api/v1/webhooks/gitlab/code-review";

    private final CodeReviewConfigRepository configRepository;
    private final CodeReviewRecordRepository recordRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodeReviewService codeReviewService;

    // ==================== 配置 CRUD ====================

    @GetMapping("/configs")
    public RX<List<CodeReviewConfigDTO>> listConfigs() {
        String userId = UserContext.getCurrentUserId();
        List<CodeReviewConfigDTO> configs = configRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toDTO)
                .toList();
        return RX.ok(configs);
    }

    @PostMapping("/configs")
    public RX<CodeReviewConfigDTO> createConfig(@RequestBody CodeReviewConfigForm form) {
        String userId = UserContext.getCurrentUserId();
        String tenantId = UserContext.getCurrentTenantId();

        CodeReviewConfigEntity entity = new CodeReviewConfigEntity();
        entity.setTenantId(tenantId);
        entity.setUserId(userId);
        entity.setGitProviderConfigId(form.getGitProviderConfigId());
        entity.setGitlabProjectId(form.getGitlabProjectId());
        entity.setProjectName(form.getProjectName());
        entity.setWorkerId(form.getWorkerId());

        // 自动生成 webhook secret token
        String secretToken = UUID.randomUUID().toString().replace("-", "");
        entity.setWebhookSecretToken(credentialEncryptor.encrypt(secretToken));

        if (form.getTriggerEvents() != null) entity.setTriggerEvents(form.getTriggerEvents());
        if (form.getReviewLanguage() != null) entity.setReviewLanguage(form.getReviewLanguage());
        if (form.getMaxDiffLines() != null) entity.setMaxDiffLines(form.getMaxDiffLines());
        if (form.getIsActive() != null) entity.setIsActive(form.getIsActive());

        configRepository.save(entity);
        log.info("Created code review config {} for project {}", entity.getId(), entity.getProjectName());

        return RX.ok(toDTO(entity));
    }

    @PutMapping("/configs/{id}")
    public RX<CodeReviewConfigDTO> updateConfig(@PathVariable String id,
                                                 @RequestBody CodeReviewConfigForm form) {
        CodeReviewConfigEntity entity = findOwnedConfig(id);

        if (form.getGitProviderConfigId() != null) entity.setGitProviderConfigId(form.getGitProviderConfigId());
        if (form.getGitlabProjectId() != null) entity.setGitlabProjectId(form.getGitlabProjectId());
        if (form.getProjectName() != null) entity.setProjectName(form.getProjectName());
        if (form.getWorkerId() != null) entity.setWorkerId(form.getWorkerId());
        if (form.getTriggerEvents() != null) entity.setTriggerEvents(form.getTriggerEvents());
        if (form.getReviewLanguage() != null) entity.setReviewLanguage(form.getReviewLanguage());
        if (form.getMaxDiffLines() != null) entity.setMaxDiffLines(form.getMaxDiffLines());
        if (form.getIsActive() != null) entity.setIsActive(form.getIsActive());

        configRepository.save(entity);
        return RX.ok(toDTO(entity));
    }

    @DeleteMapping("/configs/{id}")
    public RX<Void> deleteConfig(@PathVariable String id) {
        findOwnedConfig(id); // 验证所有权
        configRepository.deleteById(id);
        return RX.ok(null);
    }

    // ==================== 审核记录 ====================

    @GetMapping("/records")
    public RX<Page<CodeReviewRecordDTO>> listRecords(
            @RequestParam String configId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<CodeReviewRecordDTO> records = recordRepository
                .findByConfigIdOrderByCreatedAtDesc(configId, PageRequest.of(page, size))
                .map(this::toRecordDTO);
        return RX.ok(records);
    }

    @GetMapping("/records/{recordId}")
    public RX<CodeReviewRecordDTO> getRecord(@PathVariable String recordId) {
        CodeReviewRecordEntity entity = recordRepository.findByRecordId(recordId)
                .orElseThrow(() -> new IllegalArgumentException("Record not found: " + recordId));
        return RX.ok(toRecordDTO(entity));
    }

    // ==================== 手动触发 ====================

    @PostMapping("/trigger")
    public RX<CodeReviewRecordDTO> triggerReview(
            @RequestParam String configId,
            @RequestParam Long mrIid) {
        CodeReviewConfigEntity config = findOwnedConfig(configId);

        CodeReviewRecordEntity record = new CodeReviewRecordEntity();
        record.setConfigId(config.getId());
        record.setGitlabProjectId(config.getGitlabProjectId());
        record.setMrIid(mrIid);
        record.setMrTitle("Manual trigger");
        record.setAction("manual");
        record.setWorkerId(config.getWorkerId());
        recordRepository.save(record);

        codeReviewService.executeReviewAsync(config, record);

        return RX.ok(toRecordDTO(record));
    }

    // ==================== 内部方法 ====================

    /**
     * 查找配置并验证当前用户是所有者
     */
    private CodeReviewConfigEntity findOwnedConfig(String id) {
        CodeReviewConfigEntity entity = configRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Config not found: " + id));
        String userId = UserContext.getCurrentUserId();
        if (!entity.getUserId().equals(userId)) {
            throw new IllegalArgumentException("Config not found: " + id);
        }
        return entity;
    }

    private CodeReviewConfigDTO toDTO(CodeReviewConfigEntity entity) {
        CodeReviewConfigDTO dto = new CodeReviewConfigDTO();
        dto.setId(entity.getId());
        dto.setTenantId(entity.getTenantId());
        dto.setUserId(entity.getUserId());
        dto.setGitProviderConfigId(entity.getGitProviderConfigId());
        dto.setGitlabProjectId(entity.getGitlabProjectId());
        dto.setProjectName(entity.getProjectName());
        dto.setWorkerId(entity.getWorkerId());
        dto.setTriggerEvents(entity.getTriggerEvents());
        dto.setReviewLanguage(entity.getReviewLanguage());
        dto.setMaxDiffLines(entity.getMaxDiffLines());
        dto.setIsActive(entity.getIsActive());
        dto.setWebhookUrl(WEBHOOK_PATH);
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private CodeReviewRecordDTO toRecordDTO(CodeReviewRecordEntity entity) {
        CodeReviewRecordDTO dto = new CodeReviewRecordDTO();
        dto.setRecordId(entity.getRecordId());
        dto.setConfigId(entity.getConfigId());
        dto.setGitlabProjectId(entity.getGitlabProjectId());
        dto.setMrIid(entity.getMrIid());
        dto.setMrTitle(entity.getMrTitle());
        dto.setSourceBranch(entity.getSourceBranch());
        dto.setTargetBranch(entity.getTargetBranch());
        dto.setAction(entity.getAction());
        dto.setStatus(entity.getStatus());
        dto.setWorkerId(entity.getWorkerId());
        dto.setDurationMs(entity.getDurationMs());
        dto.setInlineCommentsCount(entity.getInlineCommentsCount());
        dto.setErrorMessage(entity.getErrorMessage());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }
}
