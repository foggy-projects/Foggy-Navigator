package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import com.foggy.navigator.common.enums.UserMemoryCategory;
import com.foggy.navigator.common.enums.UserMemorySource;
import com.foggy.navigator.common.form.UserMemoryForm;
import com.foggy.navigator.spi.memory.UserMemoryManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 保存用户记忆工具
 * Agent 可调用此工具将用户的偏好、重要信息保存到长期记忆
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SaveMemoryTool implements BuiltInTool {

    @Nullable
    private final UserMemoryManager userMemoryManager;

    @Override
    public String getName() {
        return "save_memory";
    }

    @Override
    public String getDescription() {
        return "保存用户的偏好、重要信息或事实到长期记忆。当用户明确要求记住某些信息，"
                + "或者对话中出现了用户的重要偏好和个人信息时使用。"
                + "类别：PREFERENCE（偏好，如编程语言、工具偏好）、FACT（事实，如姓名、角色）、NOTE（备注）。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> category = new LinkedHashMap<>();
        category.put("type", "string");
        category.put("description", "记忆类别：PREFERENCE（偏好）、FACT（事实）、NOTE（备注）");
        category.put("enum", new String[]{"PREFERENCE", "FACT", "NOTE"});
        properties.put("category", category);

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "string");
        content.put("description", "要保存的记忆内容，简洁明了的一句话描述");
        properties.put("content", content);

        return Map.of(
                "type", "object",
                "properties", properties,
                "required", new String[]{"content"}
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        if (userMemoryManager == null) {
            return ToolExecutionResult.error("MEMORY_UNAVAILABLE",
                    "记忆服务不可用，无法保存。");
        }

        String userId = request.getUserId();
        String tenantId = request.getTenantId();
        if (userId == null || userId.isBlank()) {
            return ToolExecutionResult.error("NO_USER", "无法识别当前用户。");
        }

        Map<String, Object> params = request.getParameters();
        String content = (String) params.get("content");
        if (content == null || content.isBlank()) {
            return ToolExecutionResult.error("EMPTY_CONTENT", "记忆内容不能为空。");
        }

        String categoryStr = (String) params.get("category");
        UserMemoryCategory category = UserMemoryCategory.FACT;
        if (categoryStr != null && !categoryStr.isBlank()) {
            try {
                category = UserMemoryCategory.valueOf(categoryStr);
            } catch (IllegalArgumentException e) {
                log.warn("Invalid memory category '{}', falling back to FACT", categoryStr);
            }
        }

        UserMemoryForm form = new UserMemoryForm();
        form.setCategory(category);
        form.setContent(content);

        long start = System.currentTimeMillis();
        String id = userMemoryManager.saveMemory(userId, tenantId, form, UserMemorySource.AUTO);
        long duration = System.currentTimeMillis() - start;

        log.info("Memory saved via tool: userId={}, category={}, id={}", userId, category, id);

        return ToolExecutionResult.builder()
                .success(true)
                .data("已保存到长期记忆：" + content)
                .executionTimeMs(duration)
                .build();
    }
}
