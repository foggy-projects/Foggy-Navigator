package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.BusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.dto.CreatedBusinessAgentTaskDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm;
import com.foggy.navigator.business.agent.service.BusinessAgentTaskService;
import com.foggy.navigator.common.annotation.RequireAuth;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/business-agent")
@RequiredArgsConstructor
public class BusinessAgentTaskController {

    private final BusinessAgentTaskService taskService;

    @RequireAuth(roles = "TENANT_ADMIN")
    @PostMapping("/tasks")
    public CreatedBusinessAgentTaskDTO createTask(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String userId,
            @RequestBody CreateBusinessAgentTaskForm form) {
        return taskService.createTask(tenantId, userId, form);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @GetMapping("/tasks/{taskId}")
    public BusinessAgentTaskDTO getTask(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable("taskId") String taskId) {
        return taskService.getTask(tenantId, taskId);
    }

    @RequireAuth(roles = "TENANT_ADMIN")
    @GetMapping("/sessions/{sessionId}/tasks")
    public List<BusinessAgentTaskDTO> listTasksBySession(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable("sessionId") String sessionId) {
        return taskService.listTasksBySession(tenantId, sessionId);
    }
}
