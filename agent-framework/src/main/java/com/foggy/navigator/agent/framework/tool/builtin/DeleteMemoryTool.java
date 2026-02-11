package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.common.dto.UserMemoryDTO;
import com.foggy.navigator.spi.memory.UserMemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 删除用户记忆工具
 * Agent 可调用此工具删除用户的长期记忆
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DeleteMemoryTool implements BuiltInTool {

    @Nullable
    private final UserMemoryManager userMemoryManager;

    @Override
    public String getName() {
        return "delete_memory";
    }

    @Override
    public String getDescription() {
        return "删除用户的长期记忆。当用户要求忘记某些信息、删除记忆、或纠正错误记忆时使用。"
                + "提供要删除的记忆内容关键词，将匹配并删除包含该关键词的记忆。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> keyword = new LinkedHashMap<>();
        keyword.put("type", "string");
        keyword.put("description", "要删除的记忆内容关键词，将匹配包含该关键词的记忆并删除");
        properties.put("keyword", keyword);

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"keyword"}
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (userMemoryManager == null) {
            return ToolExecutionResult.error("MEMORY_UNAVAILABLE",
                    "记忆服务不可用，无法删除。");
        }

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            return ToolExecutionResult.error("NO_USER", "无法识别当前用户。");
        }

        Map<String, Object> params = request.getParameters();
        String keyword = (String) params.get("keyword");
        if (keyword == null || keyword.isBlank()) {
            return ToolExecutionResult.error("EMPTY_KEYWORD", "关键词不能为空。");
        }

        long start = System.currentTimeMillis();

        List<UserMemoryDTO> memories = userMemoryManager.listMemories(userId);
        String lowerKeyword = keyword.toLowerCase();
        List<UserMemoryDTO> matches = memories.stream()
                .filter(m -> m.getContent().toLowerCase().contains(lowerKeyword))
                .toList();

        if (matches.isEmpty()) {
            return ToolExecutionResult.builder()
                    .success(true)
                    .data("未找到包含「" + keyword + "」的记忆。")
                    .executionTimeMs(System.currentTimeMillis() - start)
                    .build();
        }

        for (UserMemoryDTO match : matches) {
            userMemoryManager.deleteMemory(match.getId());
            log.info("Memory deleted via tool: userId={}, id={}, content={}", userId, match.getId(), match.getContent());
        }

        long duration = System.currentTimeMillis() - start;
        return ToolExecutionResult.builder()
                .success(true)
                .data("已删除 " + matches.size() + " 条包含「" + keyword + "」的记忆。")
                .executionTimeMs(duration)
                .build();
    }
}
