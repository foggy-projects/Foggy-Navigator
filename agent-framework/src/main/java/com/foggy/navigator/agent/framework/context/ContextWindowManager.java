package com.foggy.navigator.agent.framework.context;

import com.foggy.navigator.agent.framework.session.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Token-aware 上下文窗口管理器
 * 根据 token 预算从历史消息中选择最近的消息，确保不超出 LLM 上下文窗口限制
 */
@Slf4j
public class ContextWindowManager {

    /** 平均每个 token 对应的字符数（中英文混合场景取 2.5） */
    private static final double CHARS_PER_TOKEN = 2.5;

    /** 最少保留的消息数（至少保留最近一轮对话） */
    private static final int MIN_MESSAGES = 2;

    /**
     * 根据 token 预算选择历史消息
     * 从最新消息开始向前选择，直到 token 预算用尽
     *
     * @param messages        按时间正序排列的全部历史消息
     * @param maxContextTokens token 预算上限
     * @return 选中的消息列表（保持时间正序）
     */
    public List<Message> selectMessages(List<Message> messages, int maxContextTokens) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        if (maxContextTokens <= 0) {
            // 无限制，返回全部
            return new ArrayList<>(messages);
        }

        int totalTokens = 0;
        List<Message> selected = new ArrayList<>();

        // 从最新消息向前遍历
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message msg = messages.get(i);
            int msgTokens = estimateTokens(msg);

            if (selected.size() >= MIN_MESSAGES && totalTokens + msgTokens > maxContextTokens) {
                // 已满足最少消息数且超出预算，停止
                break;
            }

            selected.add(msg);
            totalTokens += msgTokens;

            // 确保最少消息数即使超出预算也保留
            if (selected.size() >= MIN_MESSAGES && totalTokens >= maxContextTokens) {
                break;
            }
        }

        // 反转恢复时间正序
        Collections.reverse(selected);

        log.debug("Context window: selected {}/{} messages, estimatedTokens={}, budget={}",
                selected.size(), messages.size(), totalTokens, maxContextTokens);

        return selected;
    }

    /**
     * 估算单条消息的 token 数
     * 使用字符数 / CHARS_PER_TOKEN 的启发式估算
     */
    public int estimateTokens(Message message) {
        if (message == null || message.getContent() == null) {
            return 0;
        }
        // 消息结构开销（role 标签等）约 4 tokens
        int overhead = 4;
        int contentTokens = (int) Math.ceil(message.getContent().length() / CHARS_PER_TOKEN);
        return overhead + contentTokens;
    }
}
