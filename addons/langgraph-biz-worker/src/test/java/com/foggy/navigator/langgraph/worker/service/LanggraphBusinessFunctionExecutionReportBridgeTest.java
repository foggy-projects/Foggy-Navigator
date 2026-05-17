package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.agent.framework.protocol.AgentMessage;
import com.foggy.navigator.agent.framework.protocol.MessageType;
import com.foggy.navigator.business.agent.service.report.BusinessFunctionExecutionReportRequest;
import com.foggy.navigator.business.agent.service.report.BusinessFunctionExecutionReportUpdate;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import com.foggy.navigator.session.event.SessionEventListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LanggraphBusinessFunctionExecutionReportBridgeTest {

    @Mock
    private LanggraphTaskRepository taskRepository;

    @Mock
    private LanggraphWorkerService workerService;

    @Mock
    private LanggraphWorkerClient workerClient;

    @Mock
    private SessionEventListener sessionEventListener;

    @Test
    void updatesReportsAndPublishesSkillFrameClose() {
        LanggraphTaskEntity task = new LanggraphTaskEntity();
        task.setTaskId("lgt_1");
        task.setSessionId("session_1");
        task.setWorkerId("worker_1");
        when(taskRepository.findByTaskId("lgt_1")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        when(workerService.getWorkerEntity("worker_1")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(workerClient);
        when(workerClient.recordBusinessFunctionResult(any())).thenReturn(Mono.just(workerResponse()));

        LanggraphBusinessFunctionExecutionReportBridge bridge =
                new LanggraphBusinessFunctionExecutionReportBridge(
                        taskRepository,
                        workerService,
                        sessionEventListener);

        BusinessFunctionExecutionReportUpdate update = bridge.updateAfterBusinessFunctionResult(
                BusinessFunctionExecutionReportRequest.builder()
                        .workerTaskId("lgt_1")
                        .suspendId("sus_1")
                        .success(true)
                        .status("SUCCESS")
                        .executionStatus("COMPLETED")
                        .content("done")
                        .functionId("tms.vehicle.create")
                        .version("v1")
                        .build());

        assertEquals("frame-report://lgt_1/root", update.getExecutionReportRef());
        assertEquals("frame-report://lgt_1/fn_1", update.getFunctionExecutionReportRef());
        assertEquals("frame-report://lgt_1/child_1", update.getChildExecutionReportRef());

        ArgumentCaptor<AgentMessage> captor = ArgumentCaptor.forClass(AgentMessage.class);
        verify(sessionEventListener).handleMessage(captor.capture());
        AgentMessage message = captor.getValue();
        assertEquals(MessageType.STATE_SYNC, message.getType());
        assertEquals("lgt_1", message.getTaskId());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) message.getPayload();
        assertEquals("skill_frame_close", payload.get("subtype"));
        assertEquals("child_1", payload.get("skillFrameId"));
        assertEquals("frame-report://lgt_1/child_1", payload.get("execution_report_ref"));
        assertEquals("COMPLETED", ((Map<?, ?>) payload.get("execution_report_digest")).get("status"));
    }

    private Map<String, Object> workerResponse() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ok", true);
        response.put("execution_report_ref", "frame-report://lgt_1/root");
        response.put("execution_report_digest", Map.of("status", "COMPLETED"));
        response.put("function_frame_id", "fn_1");
        response.put("function_execution_report_ref", "frame-report://lgt_1/fn_1");
        response.put("function_execution_report_digest", Map.of("status", "COMPLETED"));
        response.put("root_frame_id", "root");
        response.put("root_execution_report_ref", "frame-report://lgt_1/root");
        response.put("root_execution_report_digest", Map.of("status", "COMPLETED"));
        response.put("child_execution_report_ref", "frame-report://lgt_1/child_1");
        response.put("child_execution_report_digest", Map.of("status", "COMPLETED"));
        response.put("closed_skill_frames", List.of(Map.of(
                "frame_id", "child_1",
                "parent_frame_id", "root",
                "skill_id", "tms-fulfillment-agent",
                "status", "COMPLETED",
                "summary", "child done",
                "execution_report_ref", "frame-report://lgt_1/child_1",
                "execution_report_digest", Map.of("status", "COMPLETED")
        )));
        return response;
    }
}
