package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.business.agent.service.report.BusinessFunctionExecutionReportBridge;
import com.foggy.navigator.business.agent.service.report.BusinessFunctionExecutionReportFrame;
import com.foggy.navigator.business.agent.service.report.BusinessFunctionExecutionReportRequest;
import com.foggy.navigator.business.agent.service.report.BusinessFunctionExecutionReportUpdate;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Component
@RequiredArgsConstructor
public class LanggraphBusinessFunctionExecutionReportBridge implements BusinessFunctionExecutionReportBridge {

    private static final int MAX_ATTEMPTS = 3;

    private final LanggraphTaskRepository taskRepository;
    private final LanggraphWorkerService workerService;
    private final SessionEventListener sessionEventListener;

    @Override
    public BusinessFunctionExecutionReportUpdate updateAfterBusinessFunctionResult(
            BusinessFunctionExecutionReportRequest request) {
        if (request == null || !StringUtils.hasText(request.getWorkerTaskId())) {
            return null;
        }
        Optional<LanggraphTaskEntity> taskOpt = taskRepository.findByTaskId(request.getWorkerTaskId());
        if (taskOpt.isEmpty()) {
            log.debug("Skip frame report reconciliation; langgraph task not found: taskId={}",
                    request.getWorkerTaskId());
            return null;
        }
        LanggraphTaskEntity task = taskOpt.get();
        if (!StringUtils.hasText(task.getWorkerId())) {
            log.debug("Skip frame report reconciliation; workerId is blank: taskId={}",
                    request.getWorkerTaskId());
            return null;
        }

        LanggraphWorkerEntity worker = workerService.getWorkerEntity(task.getWorkerId());
        var client = workerService.createClient(worker);
        Map<String, Object> response = requestReconciliationWithRetry(client, request);
        if (response == null || !Boolean.TRUE.equals(response.get("ok"))) {
            log.debug("Frame report reconciliation did not return ok=true: taskId={}, response={}",
                    request.getWorkerTaskId(), response);
            return null;
        }

        BusinessFunctionExecutionReportUpdate update = toUpdate(response);
        publishSkillFrameCloseMessages(task, update);
        return update;
    }

    private Map<String, Object> requestReconciliationWithRetry(
            com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient client,
            BusinessFunctionExecutionReportRequest request) {
        Map<String, Object> body = toWorkerBody(request);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Map<String, Object> response = client.recordBusinessFunctionResult(body)
                    .block(Duration.ofSeconds(8));
            if (!isRetryable(response) || attempt == MAX_ATTEMPTS) {
                return response;
            }
            sleepBeforeRetry(attempt);
        }
        return null;
    }

    private Map<String, Object> toWorkerBody(BusinessFunctionExecutionReportRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        putTextIfPresent(result, "businessTaskId", request.getBusinessTaskId());
        putTextIfPresent(result, "businessSessionId", request.getBusinessSessionId());
        putTextIfPresent(result, "adapterOutputJson", request.getAdapterOutputJson());
        putTextIfPresent(result, "outputCode", request.getOutputCode());
        result.put("hasOutputData", request.isHasOutputData());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskId", request.getWorkerTaskId());
        body.put("suspendId", request.getSuspendId());
        body.put("success", request.isSuccess());
        putTextIfPresent(body, "status", request.getStatus());
        putTextIfPresent(body, "executionStatus", request.getExecutionStatus());
        putTextIfPresent(body, "content", request.getContent());
        putTextIfPresent(body, "errorMessage", request.getErrorMessage());
        putTextIfPresent(body, "functionId", request.getFunctionId());
        putTextIfPresent(body, "version", request.getVersion());
        body.put("result", result);
        return body;
    }

    private BusinessFunctionExecutionReportUpdate toUpdate(Map<String, Object> response) {
        return BusinessFunctionExecutionReportUpdate.builder()
                .executionReportRef(asText(response.get("execution_report_ref")))
                .executionReportDigest(asMap(response.get("execution_report_digest")))
                .functionFrameId(asText(response.get("function_frame_id")))
                .functionExecutionReportRef(asText(response.get("function_execution_report_ref")))
                .functionExecutionReportDigest(asMap(response.get("function_execution_report_digest")))
                .rootFrameId(asText(response.get("root_frame_id")))
                .rootExecutionReportRef(asText(response.get("root_execution_report_ref")))
                .rootExecutionReportDigest(asMap(response.get("root_execution_report_digest")))
                .childExecutionReportRef(asText(response.get("child_execution_report_ref")))
                .childExecutionReportDigest(asMap(response.get("child_execution_report_digest")))
                .closedSkillFrames(toClosedFrames(response.get("closed_skill_frames")))
                .build();
    }

    private List<BusinessFunctionExecutionReportFrame> toClosedFrames(Object value) {
        if (!(value instanceof List<?> rawList)) {
            return List.of();
        }
        List<BusinessFunctionExecutionReportFrame> frames = new ArrayList<>();
        for (Object raw : rawList) {
            if (!(raw instanceof Map<?, ?> map)) {
                continue;
            }
            frames.add(BusinessFunctionExecutionReportFrame.builder()
                    .frameId(asText(map.get("frame_id")))
                    .parentFrameId(asText(map.get("parent_frame_id")))
                    .skillId(asText(map.get("skill_id")))
                    .status(asText(map.get("status")))
                    .summary(asText(map.get("summary")))
                    .executionReportRef(asText(map.get("execution_report_ref")))
                    .executionReportDigest(asMap(map.get("execution_report_digest")))
                    .build());
        }
        return frames;
    }

    private void publishSkillFrameCloseMessages(
            LanggraphTaskEntity task,
            BusinessFunctionExecutionReportUpdate update) {
        if (update == null || update.getClosedSkillFrames() == null) {
            return;
        }
        for (BusinessFunctionExecutionReportFrame frame : update.getClosedSkillFrames()) {
            if (!StringUtils.hasText(frame.getFrameId())) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("content", StringUtils.hasText(frame.getSummary()) ? frame.getSummary() : "");
            payload.put("subtype", "skill_frame_close");
            payload.put("taskId", task.getTaskId());
            putTextIfPresent(payload, "skillFrameId", frame.getFrameId());
            putTextIfPresent(payload, "parentFrameId", frame.getParentFrameId());
            putTextIfPresent(payload, "skillId", frame.getSkillId());
            putTextIfPresent(payload, "status", frame.getStatus());
            putTextIfPresent(payload, "execution_report_ref", frame.getExecutionReportRef());
            if (frame.getExecutionReportDigest() != null) {
                payload.put("execution_report_digest", frame.getExecutionReportDigest());
            }
            AgentMessage message = AgentMessage.of(
                    task.getSessionId(),
                    LanggraphTaskService.PROVIDER_TYPE,
                    MessageType.STATE_SYNC,
                    payload);
            message.setTaskId(task.getTaskId());
            sessionEventListener.handleMessage(message);
        }
    }

    private boolean isRetryable(Map<String, Object> response) {
        return response != null
                && Boolean.FALSE.equals(response.get("ok"))
                && Boolean.TRUE.equals(response.get("retryable"));
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(150L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String asText(Object value) {
        return value instanceof String text ? text : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    private void putTextIfPresent(Map<String, Object> payload, String key, String value) {
        if (StringUtils.hasText(value)) {
            payload.put(key, value);
        }
    }
}
