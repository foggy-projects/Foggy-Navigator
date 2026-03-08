package com.foggy.navigator.codereview.service;

import com.foggy.navigator.codereview.model.entity.CodeReviewConfigEntity;
import com.foggy.navigator.codereview.model.entity.CodeReviewRecordEntity;
import com.foggy.navigator.codereview.model.enums.ReviewStatus;
import com.foggy.navigator.codereview.model.webhook.GitLabMrWebhookPayload;
import com.foggy.navigator.codereview.repository.CodeReviewConfigRepository;
import com.foggy.navigator.codereview.repository.CodeReviewRecordRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Webhook 处理服务
 * <p>
 * 验证 token、检查事件类型、去重、创建审核记录并异步触发审核。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final CodeReviewConfigRepository configRepository;
    private final CodeReviewRecordRepository recordRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodeReviewService codeReviewService;

    /**
     * 处理 GitLab MR Webhook
     * <p>
     * 使用 @Transactional 保证去重检查与记录创建的原子性，防止 TOCTOU 竞态。
     *
     * @param payload    Webhook 载荷
     * @param gitlabToken X-Gitlab-Token 请求头
     * @return true 表示已接受并开始处理，false 表示跳过
     */
    @Transactional
    public boolean processWebhook(GitLabMrWebhookPayload payload, String gitlabToken) {
        // 1. 校验事件类型
        if (!"merge_request".equals(payload.getObjectKind())) {
            log.debug("Ignoring non-MR event: {}", payload.getObjectKind());
            return false;
        }

        Long projectId = payload.getProject().getId();
        String action = payload.getObjectAttributes().getAction();
        Long mrIid = payload.getObjectAttributes().getIid();

        log.info("Received MR webhook: project={}, MR={}, action={}", projectId, mrIid, action);

        // 2. 根据 projectId 查找配置
        Optional<CodeReviewConfigEntity> configOpt = configRepository
                .findByGitlabProjectIdAndIsActiveTrue(projectId);
        if (configOpt.isEmpty()) {
            log.debug("No active code review config for project {}", projectId);
            return false;
        }

        CodeReviewConfigEntity config = configOpt.get();

        // 3. 验证 Webhook Token
        String decryptedToken = credentialEncryptor.decrypt(config.getWebhookSecretToken());
        if (!decryptedToken.equals(gitlabToken)) {
            log.warn("Webhook token mismatch for project {}", projectId);
            return false;
        }

        // 4. 检查是否在触发事件列表中
        List<String> allowedEvents = Arrays.asList(config.getTriggerEvents().split(","));
        if (!allowedEvents.contains(action)) {
            log.debug("Action '{}' not in trigger events {} for project {}",
                    action, config.getTriggerEvents(), projectId);
            return false;
        }

        // 5. 去重检查（在事务中保证原子性）
        List<CodeReviewRecordEntity> existingRecords = recordRepository
                .findByGitlabProjectIdAndMrIidAndStatusIn(
                        projectId, mrIid,
                        List.of(ReviewStatus.PENDING.name(), ReviewStatus.RUNNING.name()));
        if (!existingRecords.isEmpty()) {
            log.info("Skipping duplicate review for project={} MR={} (existing {} records)",
                    projectId, mrIid, existingRecords.size());
            return false;
        }

        // 6. 创建审核记录（在同一事务中，与去重检查原子执行）
        CodeReviewRecordEntity record = new CodeReviewRecordEntity();
        record.setConfigId(config.getId());
        record.setGitlabProjectId(projectId);
        record.setMrIid(mrIid);
        record.setMrTitle(payload.getObjectAttributes().getTitle());
        record.setSourceBranch(payload.getObjectAttributes().getSourceBranch());
        record.setTargetBranch(payload.getObjectAttributes().getTargetBranch());
        record.setAction(action);
        record.setWorkerId(config.getWorkerId());
        recordRepository.save(record);

        log.info("Created review record {} for project={} MR={}", record.getRecordId(), projectId, mrIid);

        // 7. 异步执行审核（事务提交后由 @Async 线程池执行）
        codeReviewService.executeReviewAsync(config, record);

        return true;
    }
}
