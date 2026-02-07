package com.foggy.navigator.agent.framework.context;

import com.foggy.navigator.agent.framework.session.Message;
import com.foggy.navigator.agent.framework.session.MessageRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ContextWindowManagerTest {

    private ContextWindowManager manager;

    @BeforeEach
    void setUp() {
        manager = new ContextWindowManager();
    }

    @Nested
    @DisplayName("estimateTokens 测试")
    class EstimateTokensTest {

        @Test
        @DisplayName("null 消息返回 0")
        void shouldReturnZeroForNull() {
            assertEquals(0, manager.estimateTokens(null));
        }

        @Test
        @DisplayName("空内容消息返回 0")
        void shouldReturnZeroForNullContent() {
            Message msg = Message.builder().role(MessageRole.USER).content(null).build();
            assertEquals(0, manager.estimateTokens(msg));
        }

        @Test
        @DisplayName("短消息应包含结构开销")
        void shouldIncludeOverheadForShortMessage() {
            Message msg = Message.builder().role(MessageRole.USER).content("hi").build();
            int tokens = manager.estimateTokens(msg);
            // "hi" = 2 chars / 2.5 = 1 token (ceil) + 4 overhead = 5
            assertEquals(5, tokens);
        }

        @Test
        @DisplayName("较长消息 token 估算合理")
        void shouldEstimateLongerMessage() {
            // 250 chars → 250/2.5 = 100 tokens + 4 overhead = 104
            String content = "a".repeat(250);
            Message msg = Message.builder().role(MessageRole.USER).content(content).build();
            assertEquals(104, manager.estimateTokens(msg));
        }
    }

    @Nested
    @DisplayName("selectMessages 测试")
    class SelectMessagesTest {

        @Test
        @DisplayName("null 列表返回空")
        void shouldReturnEmptyForNull() {
            List<Message> result = manager.selectMessages(null, 1000);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("空列表返回空")
        void shouldReturnEmptyForEmptyList() {
            List<Message> result = manager.selectMessages(List.of(), 1000);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("预算为 0 时返回全部消息")
        void shouldReturnAllWhenBudgetIsZero() {
            List<Message> messages = createMessages(5);
            List<Message> result = manager.selectMessages(messages, 0);
            assertEquals(5, result.size());
        }

        @Test
        @DisplayName("预算为负数时返回全部消息")
        void shouldReturnAllWhenBudgetIsNegative() {
            List<Message> messages = createMessages(5);
            List<Message> result = manager.selectMessages(messages, -1);
            assertEquals(5, result.size());
        }

        @Test
        @DisplayName("充裕预算返回全部消息")
        void shouldReturnAllWhenBudgetIsSufficient() {
            List<Message> messages = createMessages(3);
            List<Message> result = manager.selectMessages(messages, 100000);
            assertEquals(3, result.size());
            // 验证顺序保持
            assertEquals("msg-0", result.get(0).getContent());
            assertEquals("msg-1", result.get(1).getContent());
            assertEquals("msg-2", result.get(2).getContent());
        }

        @Test
        @DisplayName("有限预算应从最新消息开始选择")
        void shouldSelectFromNewestFirst() {
            // 每条消息约 "msg-X" = 5 chars / 2.5 = 2 + 4 overhead = 6 tokens
            List<Message> messages = createMessages(10);
            // 预算 20 tokens → 约能容纳 3 条消息 (3 * 6 = 18 < 20)
            List<Message> result = manager.selectMessages(messages, 20);
            assertTrue(result.size() <= 4);
            assertTrue(result.size() >= 2); // 最少保留 MIN_MESSAGES
            // 最后一条应是最新消息
            assertEquals("msg-9", result.get(result.size() - 1).getContent());
        }

        @Test
        @DisplayName("保持时间正序")
        void shouldMaintainChronologicalOrder() {
            List<Message> messages = createMessages(5);
            List<Message> result = manager.selectMessages(messages, 50);
            for (int i = 1; i < result.size(); i++) {
                String prev = result.get(i - 1).getContent();
                String curr = result.get(i).getContent();
                int prevIdx = Integer.parseInt(prev.split("-")[1]);
                int currIdx = Integer.parseInt(curr.split("-")[1]);
                assertTrue(currIdx > prevIdx, "Messages should be in chronological order");
            }
        }

        @Test
        @DisplayName("即使预算极小也保留至少 MIN_MESSAGES 条")
        void shouldKeepMinMessages() {
            List<Message> messages = createMessages(5);
            // 极小预算
            List<Message> result = manager.selectMessages(messages, 1);
            assertTrue(result.size() >= 2, "Should keep at least MIN_MESSAGES=2");
        }
    }

    private List<Message> createMessages(int count) {
        List<Message> messages = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MessageRole role = (i % 2 == 0) ? MessageRole.USER : MessageRole.ASSISTANT;
            messages.add(Message.builder()
                    .content("msg-" + i)
                    .role(role)
                    .build());
        }
        return messages;
    }
}
