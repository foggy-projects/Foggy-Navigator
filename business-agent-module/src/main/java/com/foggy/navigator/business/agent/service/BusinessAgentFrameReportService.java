package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BusinessAgentTaskEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessTaskScopedTokenEntity;
import com.foggy.navigator.business.agent.repository.BusinessAgentTaskRepository;
import com.foggy.navigator.business.agent.repository.BusinessTaskScopedTokenRepository;
import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportReader;
import com.foggy.navigator.business.agent.service.report.BusinessAgentFrameReportRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BusinessAgentFrameReportService {

    private static final int DEFAULT_MAX_CHARS = 6_000;
    private static final int MAX_ALLOWED_CHARS = 30_000;

    private final BusinessTaskScopedTokenRepository scopedTokenRepository;
    private final BusinessAgentTaskRepository taskRepository;
    private final List<BusinessAgentFrameReportReader> readers;

    @Transactional(readOnly = true)
    public Map<String, Object> getFrameReport(
            String tenantId,
            String clientAppId,
            String reportRef,
            String taskId,
            String frameId,
            String contextId,
            String sessionId,
            String mode,
            Integer maxChars) {
        if (!StringUtils.hasText(tenantId) || !StringUtils.hasText(clientAppId)) {
            throw new SecurityException("client app credential is required");
        }

        ParsedReportRef parsed = parseReportRef(reportRef, taskId, frameId);
        BusinessTaskScopedTokenEntity scopedToken = scopedTokenRepository
                .findFirstByWorkerTaskIdAndTenantIdAndClientAppIdOrderByCreatedAtDesc(
                        parsed.workerTaskId(), tenantId, clientAppId)
                .orElse(null);
        BusinessAgentTaskEntity task = scopedToken == null
                ? taskRepository.findByWorkerTaskIdAndTenantIdAndClientAppId(
                        parsed.workerTaskId(), tenantId, clientAppId).orElse(null)
                : null;

        if (scopedToken == null && task == null) {
            throw new SecurityException("frame report is not accessible for this client app");
        }

        BusinessAgentFrameReportRequest request = BusinessAgentFrameReportRequest.builder()
                .tenantId(tenantId)
                .clientAppId(clientAppId)
                .workerTaskId(parsed.workerTaskId())
                .frameId(parsed.frameId())
                .reportRef(StringUtils.hasText(reportRef) ? reportRef.trim() : null)
                .contextId(trimToNull(contextId))
                .sessionId(resolveSessionId(sessionId, scopedToken, task))
                .mode(normalizeMode(mode))
                .maxChars(normalizeMaxChars(maxChars))
                .build();

        return readers.stream()
                .filter(reader -> reader.supportsWorkerTask(request.getWorkerTaskId()))
                .findFirst()
                .map(reader -> reader.readFrameReport(request))
                .orElseThrow(() -> new IllegalStateException(
                        "no frame report reader for worker task: " + request.getWorkerTaskId()));
    }

    private ParsedReportRef parseReportRef(String reportRef, String taskId, String frameId) {
        if (StringUtils.hasText(reportRef)) {
            String trimmed = reportRef.trim();
            String prefix = "frame-report://";
            if (!trimmed.startsWith(prefix)) {
                throw new IllegalArgumentException("invalid frame report ref: " + reportRef);
            }
            String value = trimmed.substring(prefix.length());
            int separator = value.indexOf('/');
            if (separator <= 0 || separator == value.length() - 1 || value.indexOf('/', separator + 1) >= 0) {
                throw new IllegalArgumentException("invalid frame report ref: " + reportRef);
            }
            String workerTaskId = value.substring(0, separator);
            String parsedFrameId = value.substring(separator + 1);
            if (!StringUtils.hasText(workerTaskId) || !StringUtils.hasText(parsedFrameId)) {
                throw new IllegalArgumentException("invalid frame report ref: " + reportRef);
            }
            return new ParsedReportRef(workerTaskId, parsedFrameId);
        }
        if (!StringUtils.hasText(taskId) || !StringUtils.hasText(frameId)) {
            throw new IllegalArgumentException("reportRef or taskId+frameId is required");
        }
        return new ParsedReportRef(taskId.trim(), frameId.trim());
    }

    private String resolveSessionId(
            String sessionId,
            BusinessTaskScopedTokenEntity scopedToken,
            BusinessAgentTaskEntity task) {
        String requestedSessionId = trimToNull(sessionId);
        if (requestedSessionId != null) {
            return requestedSessionId;
        }
        if (scopedToken != null) {
            return Optional.ofNullable(trimToNull(scopedToken.getWorkerSessionId()))
                    .orElseGet(scopedToken::getSessionId);
        }
        return Optional.ofNullable(trimToNull(task.getWorkerSessionId()))
                .orElseGet(task::getSessionId);
    }

    private String normalizeMode(String mode) {
        String value = trimToNull(mode);
        return value == null ? "summary" : value;
    }

    private Integer normalizeMaxChars(Integer maxChars) {
        if (maxChars == null || maxChars <= 0) {
            return DEFAULT_MAX_CHARS;
        }
        return Math.min(maxChars, MAX_ALLOWED_CHARS);
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record ParsedReportRef(String workerTaskId, String frameId) {
    }
}
