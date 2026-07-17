package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Read-only, safe projection of managed Codex App Server runtime bindings. */
@Service
@Slf4j
@RequiredArgsConstructor
public class CodexAppServerProcessService {

    private final CodexAppServerEndpointRepository endpointRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodexWorkerClientFactory clientFactory;

    /**
     * App Server reports one binding per task, while a resident child can be shared.
     * The control plane therefore groups bindings by endpoint and PID instead of
     * presenting multiple misleading process rows for the same runtime.
     */
    public Map<String, Object> listProcesses(String workerId) {
        LinkedHashMap<String, SharedProcess> grouped = new LinkedHashMap<>();
        int unavailableEndpointCount = 0;

        for (CodexAppServerEndpointEntity endpoint
                : endpointRepository.findByWorkerIdOrderByUpdatedAtDesc(workerId)) {
            try {
                Map<String, Object> snapshot = clientFactory.getOrCreate(
                                "app-server-processes:" + endpoint.getEndpointId(),
                                endpoint.getEndpointUrl(),
                                credentialEncryptor.decrypt(endpoint.getAuthTokenCiphertext()))
                        .listCliProcesses()
                        .block(Duration.ofSeconds(10));
                collectSnapshot(grouped, endpoint.getEndpointId(), endpointDisplay(endpoint.getEndpointUrl()), snapshot);
            } catch (Exception error) {
                unavailableEndpointCount++;
                log.warn("Unable to read Codex App Server process snapshot for endpoint {}: type={}",
                        endpoint.getEndpointId(), error.getClass().getSimpleName());
            }
        }

        List<Map<String, Object>> processes = grouped.values().stream()
                .map(SharedProcess::toPublicMap)
                .toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("processes", processes);
        result.put("active_task_count", processes.stream()
                .mapToInt(process -> ((Number) process.get("shared_task_count")).intValue())
                .sum());
        result.put("total", processes.size());
        if (unavailableEndpointCount > 0) result.put("unavailable_endpoint_count", unavailableEndpointCount);
        return result;
    }

    static void collectSnapshot(Map<String, SharedProcess> grouped, String endpointId,
                                String endpointDisplay, Map<String, Object> snapshot) {
        if (snapshot == null || !(snapshot.get("processes") instanceof Iterable<?> entries)) return;
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> raw)) continue;
            Integer pid = integerValue(raw.get("pid"));
            if (pid == null || pid <= 0) continue;
            String taskId = safeIdentifier(raw.get("foggy_task_id"));
            // A process row without a safe Navigator task binding is not useful
            // for this control-plane view and must not become an actionable-looking
            // shared runtime record.
            if (taskId == null) continue;
            String key = endpointId + '\u0000' + pid;
            SharedProcess process = grouped.computeIfAbsent(key,
                    ignored -> new SharedProcess(pid, endpointDisplay));
            process.taskIds.add(taskId);
        }
    }

    private static Integer integerValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String safeIdentifier(Object value) {
        if (!(value instanceof String text)) return null;
        String normalized = text.trim();
        return normalized.length() <= 160 && normalized.matches("[A-Za-z0-9._:-]+") ? normalized : null;
    }

    private static String endpointDisplay(String endpointUrl) {
        try {
            java.net.URI uri = java.net.URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getScheme() == null) return "configured";
            if (host.contains(":")) host = "[" + host + "]";
            return uri.getScheme().toLowerCase() + "://" + host
                    + (uri.getPort() < 0 ? "" : ":" + uri.getPort());
        } catch (Exception ignored) {
            return "configured";
        }
    }

    static final class SharedProcess {
        private final int pid;
        private final String endpointDisplay;
        private final LinkedHashSet<String> taskIds = new LinkedHashSet<>();

        private SharedProcess(int pid, String endpointDisplay) {
            this.pid = pid;
            this.endpointDisplay = endpointDisplay;
        }

        Map<String, Object> toPublicMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("pid", pid);
            result.put("command", "codex-app-server");
            result.put("process_type", "codex-app-server");
            result.put("is_orphan", false);
            result.put("app_server_endpoint", endpointDisplay);
            result.put("shared_task_count", taskIds.size());
            result.put("foggy_task_ids", new ArrayList<>(taskIds));
            if (taskIds.size() == 1) result.put("foggy_task_id", taskIds.iterator().next());
            return result;
        }
    }
}
