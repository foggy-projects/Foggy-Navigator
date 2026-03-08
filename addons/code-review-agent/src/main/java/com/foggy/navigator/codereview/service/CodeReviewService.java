package com.foggy.navigator.codereview.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.foggy.navigator.codereview.model.dto.DiffRefs;
import com.foggy.navigator.codereview.model.dto.ReviewResult;
import com.foggy.navigator.codereview.model.entity.CodeReviewConfigEntity;
import com.foggy.navigator.codereview.model.entity.CodeReviewRecordEntity;
import com.foggy.navigator.codereview.model.enums.ReviewStatus;
import com.foggy.navigator.codereview.repository.CodeReviewRecordRepository;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 代码审核核心服务
 * <p>
 * 编排完整流程：获取 diff → 构建 prompt → 调用 Claude Worker → 解析结果 → 回写 GitLab 评论
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CodeReviewService {

    private final GitLabMrClient gitLabMrClient;
    private final ReviewPromptBuilder promptBuilder;
    private final ReviewResultParser resultParser;
    private final CodeReviewRecordRepository recordRepository;
    private final ClaudeWorkerFacade claudeWorkerFacade;

    /**
     * 异步执行代码审核
     */
    @Async("codeReviewExecutor")
    public void executeReviewAsync(CodeReviewConfigEntity config, CodeReviewRecordEntity record) {
        long startTime = System.currentTimeMillis();

        try {
            // 更新状态为 RUNNING
            record.setStatus(ReviewStatus.RUNNING.name());
            recordRepository.save(record);

            log.info("Starting code review: record={}, project={}, MR={}",
                    record.getRecordId(), record.getGitlabProjectId(), record.getMrIid());

            // 解析凭证（一次解析，全流程复用）
            GitLabMrClient.ResolvedCredentials creds =
                    gitLabMrClient.resolveCredentials(config.getGitProviderConfigId());

            // 1. 获取 MR diff
            JsonNode mrChanges = gitLabMrClient.getMrChanges(
                    creds, record.getGitlabProjectId(), record.getMrIid());

            // 2. 构建审核 prompt
            String prompt = promptBuilder.build(config, mrChanges);
            log.debug("Built review prompt ({} chars)", prompt.length());

            // 3. 调用 Claude Worker 执行审核
            Map<String, Object> result = claudeWorkerFacade.syncQuery(
                    config.getUserId(),
                    config.getWorkerId(),
                    prompt,
                    null,   // cwd - 纯文本分析不需要
                    null,   // claudeSessionId - 每次新会话
                    1,      // maxTurns=1 - 纯分析，无需工具调用
                    null    // model - 使用 Worker 默认模型
            );

            String resultText = (String) result.get("resultText");
            String error = (String) result.get("error");

            if (error != null && !error.isEmpty()) {
                throw new RuntimeException("Claude Worker returned error: " + error);
            }

            if (resultText == null || resultText.isBlank()) {
                throw new RuntimeException("Claude Worker returned empty result");
            }

            log.info("Received AI review response ({} chars)", resultText.length());

            // 4. 解析审核结果
            ReviewResult reviewResult = resultParser.parse(resultText);

            // 5. 提取 diff_refs（用于 inline comments 的 SHA 引用）
            DiffRefs diffRefs = gitLabMrClient.extractDiffRefs(mrChanges);

            // 6. 发布行级 inline 评论
            int postedCount = 0;
            List<ReviewResult.InlineComment> comments = reviewResult.getComments();
            if (comments != null && diffRefs.getBaseSha() != null) {
                for (ReviewResult.InlineComment comment : comments) {
                    try {
                        String body = ReviewResultParser.severityIcon(comment.getSeverity())
                                + " " + comment.getComment();
                        gitLabMrClient.postMrDiscussion(
                                creds,
                                record.getGitlabProjectId(),
                                record.getMrIid(),
                                comment.getFile(),
                                comment.getLine(),
                                body,
                                diffRefs);
                        postedCount++;
                    } catch (Exception e) {
                        log.warn("Failed to post inline comment on {}:{} - {}",
                                comment.getFile(), comment.getLine(), e.getMessage());
                    }
                }
            }

            // 7. 发布总结评论
            String summaryNote = formatSummaryNote(reviewResult.getSummary(), postedCount);
            gitLabMrClient.postMrNote(creds, record.getGitlabProjectId(),
                    record.getMrIid(), summaryNote);

            // 8. 更新记录
            long duration = System.currentTimeMillis() - startTime;
            record.setStatus(ReviewStatus.COMPLETED.name());
            record.setDurationMs(duration);
            record.setInlineCommentsCount(postedCount);
            recordRepository.save(record);

            log.info("Code review completed: record={}, duration={}ms, inlineComments={}",
                    record.getRecordId(), duration, postedCount);

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            log.error("Code review failed: record={}, error={}", record.getRecordId(), e.getMessage(), e);

            record.setStatus(ReviewStatus.FAILED.name());
            record.setDurationMs(duration);
            record.setErrorMessage(e.getMessage());
            recordRepository.save(record);
        }
    }

    /**
     * 格式化总结评论
     */
    private String formatSummaryNote(String summary, int inlineCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("## \uD83E\uDD16 AI Code Review\n\n");
        sb.append(summary != null ? summary : "(No summary available)");
        sb.append("\n\n---\n");
        sb.append("_Reviewed by Foggy Navigator AI");
        if (inlineCount > 0) {
            sb.append(" | ").append(inlineCount).append(" inline comments posted");
        }
        sb.append("_\n");
        return sb.toString();
    }
}
