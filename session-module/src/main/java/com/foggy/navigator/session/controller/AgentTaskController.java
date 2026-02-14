package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.entity.AgentTaskEntity;
import com.foggy.navigator.session.repository.AgentTaskRepository;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 跨 Agent 任务看板 REST API
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/agent-tasks")
@RequireAuth
@RequiredArgsConstructor
public class AgentTaskController {

    private final AgentTaskRepository agentTaskRepository;

    /**
     * 列出当前用户的所有任务（按创建时间降序）
     */
    @GetMapping
    public RX<List<Map<String, Object>>> listTasks() {
        String userId = UserContext.getCurrentUserId();
        List<Map<String, Object>> tasks = agentTaskRepository.findByUserIdOrderByCreatedAtDesc(userId)
                .stream()
                .map(this::toMap)
                .toList();
        return RX.ok(tasks);
    }

    /**
     * 列出某会话下的委派任务
     */
    @GetMapping("/session/{sessionId}")
    public RX<List<Map<String, Object>>> listTasksBySession(@PathVariable String sessionId) {
        List<Map<String, Object>> tasks = agentTaskRepository.findByParentSessionId(sessionId)
                .stream()
                .map(this::toMap)
                .toList();
        return RX.ok(tasks);
    }

    private Map<String, Object> toMap(AgentTaskEntity entity) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("taskId", entity.getTaskId());
        map.put("parentSessionId", entity.getParentSessionId());
        map.put("userId", entity.getUserId());
        map.put("sourceAgentId", entity.getSourceAgentId());
        map.put("targetAgentId", entity.getTargetAgentId());
        map.put("taskType", entity.getTaskType());
        map.put("status", entity.getStatus());
        map.put("prompt", entity.getPrompt());
        map.put("resultSummary", entity.getResultSummary());
        map.put("externalTaskId", entity.getExternalTaskId());
        map.put("createdAt", entity.getCreatedAt());
        map.put("completedAt", entity.getCompletedAt());
        return map;
    }
}
