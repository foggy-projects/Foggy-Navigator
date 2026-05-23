package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportReader;
import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BusinessAgentFrameReportServiceTest {

    @Test
    void getFrameReport_validatesClientAppAndRoutesToReader() {
        BusinessTaskScopedTokenRepository tokenRepository = mock(BusinessTaskScopedTokenRepository.class);
        BusinessAgentTaskRepository taskRepository = mock(BusinessAgentTaskRepository.class);
        AtomicReference<BusinessAgentFrameReportRequest> capturedRequest = new AtomicReference<>();
        BusinessAgentFrameReportReader reader = new BusinessAgentFrameReportReader() {
            @Override
            public boolean supportsWorkerTask(String workerTaskId) {
                return "lgt_123".equals(workerTaskId);
            }

            @Override
            public Map<String, Object> readFrameReport(BusinessAgentFrameReportRequest request) {
                capturedRequest.set(request);
                return Map.of("ok", true, "markdown", "# report");
            }
        };

        BusinessTaskScopedTokenEntity token = new BusinessTaskScopedTokenEntity();
        token.setWorkerTaskId("lgt_123");
        token.setWorkerSessionId("worker-session-1");
        token.setSessionId("business-session-1");
        when(tokenRepository.findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "lgt_123", "tenant-1", "app-1")).thenReturn(Optional.of(token));

        BusinessAgentFrameReportService service = new BusinessAgentFrameReportService(
                tokenRepository,
                taskRepository,
                List.of(reader));

        Map<String, Object> report = service.getFrameReport(
                "tenant-1",
                "app-1",
                "frame-report://lgt_123/frm_456",
                null,
                null,
                "ctx-1",
                null,
                "markdown",
                50_000);

        assertEquals(true, report.get("ok"));
        assertEquals("# report", report.get("markdown"));
        BusinessAgentFrameReportRequest request = capturedRequest.get();
        assertEquals("lgt_123", request.getWorkerTaskId());
        assertEquals("frm_456", request.getFrameId());
        assertEquals("worker-session-1", request.getSessionId());
        assertEquals("markdown", request.getMode());
        assertEquals(30_000, request.getMaxChars());
    }

    @Test
    void getFrameReport_rejectsTaskOutsideClientApp() {
        BusinessTaskScopedTokenRepository tokenRepository = mock(BusinessTaskScopedTokenRepository.class);
        BusinessAgentTaskRepository taskRepository = mock(BusinessAgentTaskRepository.class);
        when(tokenRepository.findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                "lgt_123", "tenant-1", "app-1")).thenReturn(Optional.empty());
        when(taskRepository.findByWorkerTaskIdAndTenantIdAndClientAppId(
                "lgt_123", "tenant-1", "app-1")).thenReturn(Optional.empty());

        BusinessAgentFrameReportService service = new BusinessAgentFrameReportService(
                tokenRepository,
                taskRepository,
                List.of(mock(BusinessAgentFrameReportReader.class)));

        SecurityException error = assertThrows(SecurityException.class, () -> service.getFrameReport(
                "tenant-1",
                "app-1",
                "frame-report://lgt_123/frm_456",
                null,
                null,
                null,
                null,
                "markdown",
                10_000));

        assertEquals("frame report is not accessible for this client app", error.getMessage());
        verify(taskRepository).findByWorkerTaskIdAndTenantIdAndClientAppId("lgt_123", "tenant-1", "app-1");
    }
}
