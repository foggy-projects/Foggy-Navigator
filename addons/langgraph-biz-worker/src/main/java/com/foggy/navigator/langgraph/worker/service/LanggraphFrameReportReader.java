package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportReader;
import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportRequest;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class LanggraphFrameReportReader implements BusinessAgentFrameReportReader {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(30);

    private final LanggraphTaskRepository taskRepository;
    private final LanggraphWorkerService workerService;

    @Override
    public boolean supportsWorkerTask(String workerTaskId) {
        return StringUtils.hasText(workerTaskId) && taskRepository.findByTaskId(workerTaskId).isPresent();
    }

    @Override
    public Map<String, Object> readFrameReport(BusinessAgentFrameReportRequest request) {
        LanggraphTaskEntity task = taskRepository.findByTaskId(request.getWorkerTaskId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "LangGraph task not found: " + request.getWorkerTaskId()));
        if (StringUtils.hasText(task.getTenantId()) && !task.getTenantId().equals(request.getTenantId())) {
            throw new SecurityException("LangGraph task tenant mismatch");
        }

        LanggraphWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        if (StringUtils.hasText(worker.getTenantId()) && !worker.getTenantId().equals(request.getTenantId())) {
            throw new SecurityException("LangGraph worker tenant mismatch");
        }

        Map<String, Object> response = workerService.createClient(worker)
                .getFrameReport(
                        request.getReportRef(),
                        request.getWorkerTaskId(),
                        request.getFrameId(),
                        firstText(request.getContextId(), task.getContextId()),
                        firstText(request.getSessionId(), task.getSessionId()),
                        request.getMode(),
                        request.getMaxChars())
                .block(READ_TIMEOUT);
        if (response != null) {
            return response;
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("ok", false);
        fallback.put("error", "empty frame report response");
        fallback.put("report_ref", request.getReportRef());
        fallback.put("task_id", request.getWorkerTaskId());
        fallback.put("frame_id", request.getFrameId());
        return fallback;
    }

    private String firstText(String preferred, String fallback) {
        return StringUtils.hasText(preferred) ? preferred : fallback;
    }
}
