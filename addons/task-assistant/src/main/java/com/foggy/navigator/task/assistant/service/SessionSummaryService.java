package com.foggy.navigator.task.assistant.service;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.SessionManager;
import com.foggy.navigator.spi.claude.ClaudeWorkerFacade;
import com.foggy.navigator.spi.claude.ConversationStateQuery;
import com.foggy.navigator.task.assistant.entity.TaskAssistantConfigEntity;
import com.foggy.navigator.task.assistant.repository.TaskAssistantConfigRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话摘要定时生成服务
 * <p>
 * 定时扫描 ARCHIVED / ON_HOLD 状态的会话，对 summary 为空的会话调用 LLM 生成摘要，
 * 写回 SessionEntity.summary 字段，为后续检索做准备。
 * <p>
 * 按用户维度处理：每个用户的会话使用该用户自己的 TaskAssistantConfig 调用 LLM，
 * 与 TaskAssistantService 的事件处理模式保持一致。
 * <p>
 * 使用 ClaudeWorkerFacade.syncQuery()（轻量调用，maxTurns=1，不创建 AgentTask 记录）。
 */
@Slf4j
@Service
public class SessionSummaryService {

    /** 每轮每个用户最多处理的会话数，防止批量归档时 LLM 压力过大 */
    private static final int BATCH_SIZE_PER_USER = 5;

    /** 摘要 prompt 中取最近消息的条数 */
    private static final int RECENT_MESSAGE_COUNT = 5;

    /** 需要生成摘要的交互状态 */
    private static final List<String> TARGET_STATES = List.of("ARCHIVED", "ON_HOLD");

    private final TaskAssistantConfigRepository configRepository;
    @Nullable
    private final ConversationStateQuery conversationStateQuery;
    @Nullable
    private final SessionManager sessionManager;
    @Nullable
    private final ClaudeWorkerFacade claudeWorkerFacade;

    public SessionSummaryService(TaskAssistantConfigRepository configRepository,
                                  @Nullable ConversationStateQuery conversationStateQuery,
                                  @Nullable SessionManager sessionManager,
                                  @Nullable ClaudeWorkerFacade claudeWorkerFacade) {
        this.configRepository = configRepository;
        this.conversationStateQuery = conversationStateQuery;
        this.sessionManager = sessionManager;
        this.claudeWorkerFacade = claudeWorkerFacade;
    }

    /**
     * 每 10 分钟扫描一次，为归档/搁置的会话生成 AI 摘要。
     * 按用户维度遍历：每个用户的会话使用该用户自己的助手配置调用 LLM。
     */
    @Scheduled(cron = "0 */10 * * * *")
    public void generatePendingSummaries() {
        if (conversationStateQuery == null || sessionManager == null || claudeWorkerFacade == null) {
            return;
        }

        try {
            // 1. 按用户分组查询 ARCHIVED / ON_HOLD 状态的 sessionId
            Map<String, List<String>> userSessionMap =
                    conversationStateQuery.findSessionIdsByStatesGroupByUser(TARGET_STATES);
            if (userSessionMap.isEmpty()) {
                return;
            }

            int totalProcessed = 0;
            int totalSuccess = 0;

            // 2. 按用户逐个处理
            for (Map.Entry<String, List<String>> entry : userSessionMap.entrySet()) {
                String userId = entry.getKey();
                List<String> sessionIds = entry.getValue();

                // 2a. 查找该用户的助手配置
                TaskAssistantConfigEntity config = configRepository.findByUserId(userId).orElse(null);
                if (config == null || !Boolean.TRUE.equals(config.getEnabled())
                        || config.getWorkerId() == null || config.getCwd() == null
                        || config.getModel() == null) {
                    log.debug("Session summary: no available config for userId={}, skipping {} sessions",
                            userId, sessionIds.size());
                    continue;
                }

                // 2b. 过滤出 summary 为 null 的
                List<String> pendingIds = sessionManager.findSessionIdsWithoutSummary(sessionIds);
                if (pendingIds.isEmpty()) {
                    continue;
                }

                // 2c. 批量处理（每用户限流）
                List<String> batch = pendingIds.stream().limit(BATCH_SIZE_PER_USER).collect(Collectors.toList());
                int successCount = 0;

                for (String sessionId : batch) {
                    try {
                        boolean ok = generateSummaryForSession(sessionId, config);
                        if (ok) successCount++;
                    } catch (Exception e) {
                        log.error("Session summary: failed for sessionId={}, userId={}", sessionId, userId, e);
                    }
                }

                totalProcessed += batch.size();
                totalSuccess += successCount;

                log.info("Session summary: userId={}, processed {}/{}, success={}",
                        userId, batch.size(), pendingIds.size(), successCount);
            }

            if (totalProcessed > 0) {
                log.info("Session summary: total processed={}, success={}", totalProcessed, totalSuccess);
            }
        } catch (Exception e) {
            log.error("Session summary: scheduled task failed", e);
        }
    }

    /**
     * 为单个会话生成摘要
     */
    private boolean generateSummaryForSession(String sessionId, TaskAssistantConfigEntity config) {
        // 1. 获取消息（首条 + 最近5条）
        List<Message> messages = sessionManager.getFirstAndRecentMessages(sessionId, RECENT_MESSAGE_COUNT);
        if (messages.isEmpty()) {
            log.debug("Session summary: no messages for sessionId={}, skipping", sessionId);
            return false;
        }

        // 2. 获取会话标题
        var session = sessionManager.getSession(sessionId);
        String title = (session != null && session.getTaskName() != null) ? session.getTaskName() : "(无标题)";

        // 3. 构建 prompt
        String prompt = buildSummaryPrompt(title, messages);

        // 4. 调用 LLM（syncQuery，轻量，不创建 AgentTask）
        Map<String, Object> result = claudeWorkerFacade.syncQuery(
                config.getUserId(),
                config.getWorkerId(),
                prompt,
                config.getCwd(),
                null,   // 不复用会话（每次独立）
                1,      // maxTurns=1，纯文本输出，无工具调用
                config.getModel()
        );

        String error = (String) result.get("error");
        if (error != null) {
            log.warn("Session summary: LLM error for sessionId={}: {}", sessionId, error);
            return false;
        }

        String summaryText = (String) result.get("resultText");
        if (summaryText == null || summaryText.isBlank()) {
            log.warn("Session summary: empty response for sessionId={}", sessionId);
            return false;
        }

        // 5. 清理并写回
        String cleanSummary = cleanSummary(summaryText);
        sessionManager.updateSessionSummary(sessionId, cleanSummary);

        log.debug("Session summary: generated for sessionId={}, length={}", sessionId, cleanSummary.length());
        return true;
    }

    /**
     * 构建摘要生成 prompt
     */
    private String buildSummaryPrompt(String title, List<Message> messages) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是会话摘要生成器。请根据以下会话信息生成简洁的中文摘要（100-200字），");
        sb.append("概括会话的主题、关键操作和结果。\n\n");

        sb.append("## 会话标题\n");
        sb.append(title).append("\n\n");

        if (messages.size() == 1) {
            sb.append("## 消息内容\n");
            appendMessage(sb, messages.get(0));
        } else {
            sb.append("## 首条消息\n");
            appendMessage(sb, messages.get(0));
            sb.append("\n## 最近消息\n");
            for (int i = 1; i < messages.size(); i++) {
                appendMessage(sb, messages.get(i));
            }
        }

        sb.append("\n请直接输出摘要文本，不要加标题或格式标记。");
        return sb.toString();
    }

    private void appendMessage(StringBuilder sb, Message msg) {
        String role = msg.getRole() != null ? msg.getRole().name() : "UNKNOWN";
        String content = msg.getContent();
        // 截断过长的消息内容（单条最多 2000 字符）
        if (content != null && content.length() > 2000) {
            content = content.substring(0, 2000) + "...(截断)";
        }
        sb.append("**").append(role).append("**: ").append(content != null ? content : "(空)").append("\n\n");
    }

    /**
     * 清理 LLM 返回的摘要文本（去掉可能的 markdown 代码块包裹等）
     */
    private String cleanSummary(String raw) {
        String text = raw.strip();
        // 去掉可能的 markdown 代码块包裹
        if (text.startsWith("```") && text.endsWith("```")) {
            int firstNewline = text.indexOf('\n');
            int lastBacktick = text.lastIndexOf("```");
            if (firstNewline > 0 && lastBacktick > firstNewline) {
                text = text.substring(firstNewline + 1, lastBacktick).strip();
            }
        }
        // 去掉首尾引号（普通双引号或中文双引号 \u201C \u201D）
        if ((text.startsWith("\"") && text.endsWith("\""))
                || (text.startsWith("\u201C") && text.endsWith("\u201D"))) {
            text = text.substring(1, text.length() - 1).strip();
        }
        return text;
    }
}
