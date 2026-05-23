package com.foggy.navigator.langgraph.worker.service;

import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportRequest;
import com.foggy.navigator.langgraph.worker.client.LanggraphWorkerClient;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphTaskEntity;
import com.foggy.navigator.langgraph.worker.model.entity.LanggraphWorkerEntity;
import com.foggy.navigator.langgraph.worker.repository.LanggraphTaskRepository;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class LanggraphFrameReportReaderTest {

    @Test
    void readFrameReport_allowsSharedWorkerWithoutTenant() {
        LanggraphTaskRepository taskRepository = mock(LanggraphTaskRepository.class);
        LanggraphWorkerService workerService = mock(LanggraphWorkerService.class);
        LanggraphWorkerClient client = mock(LanggraphWorkerClient.class);
        LanggraphFrameReportReader reader = new LanggraphFrameReportReader(taskRepository, workerService);

        LanggraphTaskEntity task = task("lgt_01", "88800", "worker_01");
        task.setContextId("ctx_task");
        task.setSessionId("session_task");
        when(taskRepository.findByTaskId("lgt_01")).thenReturn(Optional.of(task));

        LanggraphWorkerEntity worker = worker("worker_01", null);
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);
        when(workerService.createClient(worker)).thenReturn(client);

        Map<String, Object> response = Map.of("ok", true, "markdown", "# Report");
        when(client.getFrameReport(
                "frame-report://lgt_01/frm_01",
                "lgt_01",
                "frm_01",
                "ctx_task",
                "session_task",
                "markdown",
                30000
        )).thenReturn(Mono.just(response));

        Map<String, Object> result = reader.readFrameReport(request("88800"));

        assertSame(response, result);
    }

    @Test
    void readFrameReport_rejectsWorkerTenantMismatch() {
        LanggraphTaskRepository taskRepository = mock(LanggraphTaskRepository.class);
        LanggraphWorkerService workerService = mock(LanggraphWorkerService.class);
        LanggraphFrameReportReader reader = new LanggraphFrameReportReader(taskRepository, workerService);

        when(taskRepository.findByTaskId("lgt_01"))
                .thenReturn(Optional.of(task("lgt_01", "88800", "worker_01")));
        LanggraphWorkerEntity worker = worker("worker_01", "tenant_upstream_sandbox");
        when(workerService.getWorkerEntity("worker_01")).thenReturn(worker);

        SecurityException error = assertThrows(SecurityException.class,
                () -> reader.readFrameReport(request("88800")));

        assertEquals("LangGraph worker tenant mismatch", error.getMessage());
        verify(workerService, never()).createClient(any());
    }

    private BusinessAgentFrameReportRequest request(String tenantId) {
        return BusinessAgentFrameReportRequest.builder()
                .tenantId(tenantId)
                .clientAppId("app_01")
                .workerTaskId("lgt_01")
                .frameId("frm_01")
                .reportRef("frame-report://lgt_01/frm_01")
                .mode("markdown")
                .maxChars(30000)
                .build();
    }

    private LanggraphTaskEntity task(String taskId, String tenantId, String workerId) {
        LanggraphTaskEntity task = new LanggraphTaskEntity();
        task.setTaskId(taskId);
        task.setTenantId(tenantId);
        task.setWorkerId(workerId);
        return task;
    }

    private LanggraphWorkerEntity worker(String workerId, String tenantId) {
        LanggraphWorkerEntity worker = new LanggraphWorkerEntity();
        worker.setWorkerId(workerId);
        worker.setTenantId(tenantId);
        return worker;
    }
}
