package com.foggy.navigator.langgraph.worker.controller;

import com.foggyframework.core.ex.RX;
import com.foggy.navigator.langgraph.worker.model.dto.LanggraphTaskDTO;
import com.foggy.navigator.langgraph.worker.service.LanggraphTaskService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/langgraph-tasks")
@RequiredArgsConstructor
public class LanggraphTaskController {

    private final LanggraphTaskService taskService;

    @GetMapping("/{taskId}")
    public RX<LanggraphTaskDTO> getTask(@PathVariable String taskId,
                                         @RequestParam String userId) {
        return RX.ok(taskService.getTask(userId, taskId));
    }
}
