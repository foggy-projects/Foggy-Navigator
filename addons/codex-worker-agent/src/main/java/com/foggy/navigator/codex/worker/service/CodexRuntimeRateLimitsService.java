package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.client.CodexWorkerClient;
import com.foggy.navigator.codex.worker.client.CodexWorkerClientFactory;
import com.foggy.navigator.codex.worker.model.dto.CodexRuntimeRateLimitsDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class CodexRuntimeRateLimitsService {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(12);

    private final CodexRuntimeRepository runtimeRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodexWorkerClientFactory clientFactory;

    public CodexRuntimeRateLimitsDTO read(String runtimeId, int revision, boolean refresh) {
        CodexRuntimeEntity runtime = runtimeRepository.findByRuntimeIdAndRevision(runtimeId, revision)
                .orElseThrow(() -> new IllegalArgumentException(
                        "CODEX_RUNTIME_REVISION_NOT_FOUND: " + runtimeId + ":" + revision));
        if (!"APP_SERVER".equals(runtime.getRuntimeType())) {
            throw new IllegalArgumentException("CODEX_RUNTIME_RATE_LIMITS_UNSUPPORTED");
        }
        if (runtime.getInstanceId() == null || runtime.getInstanceId().isBlank()) {
            throw new IllegalStateException("CODEX_RUNTIME_INSTANCE_NOT_PINNED");
        }

        String authToken = credentialEncryptor.decrypt(runtime.getAuthTokenCiphertext());
        CodexWorkerClient client = clientFactory.getOrCreate(
                clientKey(runtime), runtime.getEndpointUrl(), authToken, runtime.getInstanceId());
        CodexRuntimeRateLimitsDTO snapshot;
        try {
            snapshot = client.getRuntimeRateLimits(refresh).block(READ_TIMEOUT);
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() != HttpStatus.NOT_FOUND) {
                throw e;
            }
            return unsupportedSnapshot(runtime);
        }
        if (snapshot == null
                || !Objects.equals(runtime.getRuntimeId(), snapshot.getRuntimeId())
                || !Objects.equals(runtime.getRevision(), snapshot.getRuntimeRevision())
                || !Objects.equals(runtime.getInstanceId(), snapshot.getInstanceId())) {
            throw new IllegalStateException("CODEX_RUNTIME_RATE_LIMITS_IDENTITY_MISMATCH");
        }
        if (!Objects.equals(1, snapshot.getContractVersion())
                || !"DEFAULT_CODEX_HOME".equals(snapshot.getScope())
                || snapshot.getState() == null
                || snapshot.getStale() == null
                || snapshot.getLimits() == null) {
            throw new IllegalStateException("CODEX_RUNTIME_RATE_LIMITS_CONTRACT_MISMATCH");
        }
        return snapshot;
    }

    private CodexRuntimeRateLimitsDTO unsupportedSnapshot(CodexRuntimeEntity runtime) {
        return CodexRuntimeRateLimitsDTO.builder()
                .contractVersion(1)
                .runtimeId(runtime.getRuntimeId())
                .runtimeRevision(runtime.getRevision())
                .instanceId(runtime.getInstanceId())
                .scope("DEFAULT_CODEX_HOME")
                .state(CodexRuntimeRateLimitsDTO.State.UNSUPPORTED)
                .observedAtEpochMs(null)
                .stale(false)
                .limits(List.of())
                .errorCode("RATE_LIMITS_UNSUPPORTED")
                .build();
    }

    private String clientKey(CodexRuntimeEntity runtime) {
        return "rate-limits:" + runtime.getRuntimeId() + ":" + runtime.getRevision();
    }
}
