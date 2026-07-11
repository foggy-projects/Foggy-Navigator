package com.foggy.navigator.codex.worker.service;

import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointDTO;
import com.foggy.navigator.codex.worker.model.dto.CodexAppServerEndpointSyncDTO;
import com.foggy.navigator.codex.worker.model.entity.CodexAppServerEndpointEntity;
import com.foggy.navigator.codex.worker.model.entity.CodexRuntimeEntity;
import com.foggy.navigator.codex.worker.model.form.CodexAppServerEndpointForm;
import com.foggy.navigator.codex.worker.repository.CodexAppServerEndpointRepository;
import com.foggy.navigator.codex.worker.repository.CodexRuntimeRepository;
import com.foggy.navigator.common.security.CredentialEncryptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** CRUD for App Server connection profiles. Runtime creation happens only via sync. */
@Service
@RequiredArgsConstructor
public class CodexAppServerEndpointService {

    private final CodexAppServerEndpointRepository endpointRepository;
    private final CodexRuntimeRepository runtimeRepository;
    private final CredentialEncryptor credentialEncryptor;
    private final CodexRuntimeRegistryService runtimeRegistryService;

    @Transactional
    public CodexAppServerEndpointDTO create(CodexAppServerEndpointForm form) {
        validateCreate(form);
        CodexAppServerEndpointEntity endpoint = new CodexAppServerEndpointEntity();
        endpoint.setEndpointId("endpoint-" + UUID.randomUUID().toString().replace("-", ""));
        endpoint.setWorkerId(form.getWorkerId().trim());
        endpoint.setEndpointUrl(trimTrailingSlash(form.getEndpointUrl()));
        endpoint.setAuthTokenCiphertext(credentialEncryptor.encrypt(optionalToken(form.getAuthToken())));
        endpoint.setConfigurationVersion(1L);
        endpoint.setLastSyncStatus("PENDING");
        return toDTO(endpointRepository.save(endpoint));
    }

    @Transactional(readOnly = true)
    public List<CodexAppServerEndpointDTO> listByWorker(String workerId) {
        return endpointRepository.findByWorkerIdOrderByUpdatedAtDesc(workerId).stream()
                .map(this::toDTO)
                .toList();
    }

    @Transactional
    public CodexAppServerEndpointDTO update(String endpointId, CodexAppServerEndpointForm form) {
        if (form == null) throw new IllegalArgumentException("endpoint update is required");
        CodexAppServerEndpointEntity endpoint = requireEndpointForUpdate(endpointId);
        if (form.getWorkerId() != null && !form.getWorkerId().isBlank()
                && !endpoint.getWorkerId().equals(form.getWorkerId().trim())) {
            throw new IllegalArgumentException("workerId cannot be changed for an endpoint");
        }
        boolean changed = false;
        if (form.getEndpointUrl() != null) {
            requireEndpoint(form.getEndpointUrl());
            String endpointUrl = trimTrailingSlash(form.getEndpointUrl());
            if (!endpointUrl.equals(endpoint.getEndpointUrl())) {
                endpoint.setEndpointUrl(endpointUrl);
                changed = true;
            }
        }
        if (Boolean.TRUE.equals(form.getClearAuthToken())) {
            endpoint.setAuthTokenCiphertext(credentialEncryptor.encrypt(""));
            changed = true;
        } else if (form.getAuthToken() != null && !form.getAuthToken().isBlank()) {
            validateOptionalText(form.getAuthToken(), "authToken", 4096);
            endpoint.setAuthTokenCiphertext(credentialEncryptor.encrypt(form.getAuthToken().trim()));
            changed = true;
        }
        if (changed) {
            endpoint.setConfigurationVersion(endpoint.getConfigurationVersion() + 1);
            endpoint.setLastSyncStatus("PENDING");
            endpoint.setLastSyncMessage("ENDPOINT_CONFIGURATION_CHANGED");
            endpoint.setLastSyncedAt(null);
        }
        return toDTO(endpointRepository.save(endpoint));
    }

    @Transactional
    public void delete(String endpointId) {
        CodexAppServerEndpointEntity endpoint = requireEndpointForUpdate(endpointId);
        for (CodexRuntimeEntity runtime : runtimeRepository.findByEndpointIdOrderByRevisionDesc(endpointId)) {
            if (runtime.getArchivedAt() != null) continue;
            runtime.setEnabled(false);
            runtime.setRoutingPolicy("DRAINING");
            runtime.setRolloutPercentage(0);
            runtime.setRoutingEpoch(runtime.getRoutingEpoch() + 1);
        }
        endpointRepository.delete(endpoint);
    }

    public CodexAppServerEndpointSyncDTO synchronize(String endpointId) {
        return runtimeRegistryService.synchronizeEndpoint(endpointId);
    }

    @Transactional(readOnly = true)
    public String ownerWorkerId(String endpointId) {
        return endpointRepository.findByEndpointId(endpointId)
                .map(CodexAppServerEndpointEntity::getWorkerId)
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + endpointId));
    }

    private CodexAppServerEndpointEntity requireEndpointForUpdate(String endpointId) {
        if (endpointId == null || endpointId.isBlank()) {
            throw new IllegalArgumentException("endpointId is required");
        }
        return endpointRepository.findByEndpointIdForUpdate(endpointId)
                .orElseThrow(() -> new IllegalArgumentException("Endpoint not found: " + endpointId));
    }

    private void validateCreate(CodexAppServerEndpointForm form) {
        if (form == null) throw new IllegalArgumentException("endpoint configuration is required");
        requireIdentifier(form.getWorkerId(), "workerId", 64);
        requireEndpoint(form.getEndpointUrl());
        validateOptionalText(form.getAuthToken(), "authToken", 4096);
    }

    private void requireEndpoint(String endpointUrl) {
        if (endpointUrl == null || endpointUrl.isBlank()) {
            throw new IllegalArgumentException("endpointUrl is required");
        }
        if (endpointUrl.length() > 512 || endpointUrl.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("endpointUrl contains control characters or exceeds 512");
        }
        try {
            URI uri = URI.create(endpointUrl);
            if (!(("http").equalsIgnoreCase(uri.getScheme()) || ("https").equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("endpointUrl must be an absolute http(s) URL");
            }
            if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException(
                        "endpointUrl must not contain userinfo, query parameters, or fragments");
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("endpointUrl is invalid", e);
        }
    }

    private CodexAppServerEndpointDTO toDTO(CodexAppServerEndpointEntity endpoint) {
        return CodexAppServerEndpointDTO.builder()
                .endpointId(endpoint.getEndpointId())
                .workerId(endpoint.getWorkerId())
                .endpointUrl(endpoint.getEndpointUrl())
                .endpointDisplay(endpointOrigin(endpoint.getEndpointUrl()))
                .tokenConfigured(!credentialEncryptor.decrypt(endpoint.getAuthTokenCiphertext()).isBlank())
                .configurationVersion(endpoint.getConfigurationVersion())
                .lastSyncStatus(endpoint.getLastSyncStatus())
                .lastSyncMessage(endpoint.getLastSyncMessage())
                .lastSyncedAt(endpoint.getLastSyncedAt())
                .lastRuntimeId(endpoint.getLastRuntimeId())
                .lastRuntimeRevision(endpoint.getLastRuntimeRevision())
                .createdAt(endpoint.getCreatedAt())
                .updatedAt(endpoint.getUpdatedAt())
                .build();
    }

    private String endpointOrigin(String endpointUrl) {
        try {
            URI uri = URI.create(endpointUrl);
            String host = uri.getHost();
            if (host == null || host.isBlank() || uri.getScheme() == null) return "configured";
            if (host.contains(":")) host = "[" + host + "]";
            String port = uri.getPort() >= 0 ? ":" + uri.getPort() : "";
            return uri.getScheme().toLowerCase(Locale.ROOT) + "://" + host + port;
        } catch (Exception e) {
            return "configured";
        }
    }

    private void requireIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength
                || !value.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException(field + " contains unsupported characters or exceeds " + maxLength);
        }
    }

    private void validateOptionalText(String value, String field, int maxLength) {
        if (value != null && (value.length() > maxLength || value.chars().anyMatch(Character::isISOControl))) {
            throw new IllegalArgumentException(field + " contains control characters or exceeds " + maxLength);
        }
    }

    private String trimTrailingSlash(String value) {
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String optionalToken(String value) {
        return value == null ? "" : value.trim();
    }
}
