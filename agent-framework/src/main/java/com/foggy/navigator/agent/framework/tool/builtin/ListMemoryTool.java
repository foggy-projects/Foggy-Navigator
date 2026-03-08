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

import java.util.List;
import java.util.Map;

/**
 * 查看用户记忆工具
 * Agent 可调用此工具查看用户的长期记忆列表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ListMemoryTool implements BuiltInTool {

    @Nullable
    private final UserMemoryManager userMemoryManager;

    @Override
    public String getName() {
        return "list_memory";
    }

    @Override
    public String getDescription() {
        return "查看当前用户的所有长期记忆。当用户问'你记得我什么'、'我有哪些记忆'、'列出我的记忆'时使用。";
    }

    @Override
    public Map<String, Object> getParameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of()
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (userMemoryManager == null) {
            return ToolExecutionResult.error("MEMORY_UNAVAILABLE",
                    "记忆服务不可用。");
        }

        String userId = request.getUserId();
        if (userId == null || userId.isBlank()) {
            return ToolExecutionResult.error("NO_USER", "无法识别当前用户。");
        }

        long start = System.currentTimeMillis();
        List<UserMemoryDTO> memories = userMemoryManager.listMemories(userId);
        long duration = System.currentTimeMillis() - start;

        if (memories.isEmpty()) {
            return ToolExecutionResult.builder()
                    .success(true)
                    .data("当前没有保存任何记忆。")
                    .executionTimeMs(duration)
                    .build();
        }

        StringBuilder sb = new StringBuilder();
        sb.append("当前共有 ").append(memories.size()).append(" 条记忆：\n\n");
        for (UserMemoryDTO m : memories) {
            sb.append("- [").append(m.getCategory()).append("] ").append(m.getContent());
            sb.append(" (来源: ").append(m.getSource()).append(")\n");
        }

        log.info("Listed {} memories for userId={}", memories.size(), userId);

        return ToolExecutionResult.builder()
                .success(true)
                .data(sb.toString().stripTrailing())
                .executionTimeMs(duration)
                .build();
    }
}
