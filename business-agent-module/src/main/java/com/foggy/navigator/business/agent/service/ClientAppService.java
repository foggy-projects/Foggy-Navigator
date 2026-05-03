package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppProvisioningCredentialEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppRuntimeCredentialEntity;
import com.foggy.navigator.business.agent.model.form.CreateClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueProvisioningCredentialForm;
import com.foggy.navigator.business.agent.model.form.IssueRuntimeCredentialForm;
import com.foggy.navigator.business.agent.repository.ClientAppProvisioningCredentialRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRuntimeCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ClientAppService {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_DISABLED = "DISABLED";
    public static final String STATUS_SUSPENDED = "SUSPENDED";
    public static final String STATUS_USED = "USED";

    private final ClientAppRepository clientAppRepository;
    private final ClientAppProvisioningCredentialRepository provisioningCredentialRepository;
    private final ClientAppRuntimeCredentialRepository runtimeCredentialRepository;

    @Transactional
    public IssuedCredentialDTO issueProvisioningCredential(String currentTenantId, String issuedByUserId,
                                                           IssueProvisioningCredentialForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String targetTenantId = StringUtils.hasText(form.getTargetTenantId())
                ? form.getTargetTenantId()
                : currentTenantId;
        requireText(targetTenantId, "targetTenantId is required");
        if (StringUtils.hasText(currentTenantId) && !currentTenantId.equals(targetTenantId)) {
            throw new IllegalArgumentException("provisioning credential tenant mismatch");
        }

        String token = SecretTokenSupport.randomToken("cap_");
        ClientAppProvisioningCredentialEntity entity = new ClientAppProvisioningCredentialEntity();
        entity.setCredentialId("capc_" + UUID.randomUUID());
        entity.setTokenHash(SecretTokenSupport.sha256(token));
        entity.setTenantId(targetTenantId);
        entity.setIssuedByUserId(issuedByUserId);
        entity.setOwnerUserId(form.getOwnerUserId());
        entity.setCapabilityDomain(form.getCapabilityDomain());
        entity.setAuditTag(form.getAuditTag());
        entity.setMaxUses(form.getMaxUses() == null || form.getMaxUses() <= 0 ? 1 : form.getMaxUses());
        entity.setUsedCount(0);
        entity.setStatus(STATUS_ACTIVE);
        entity.setExpiresAt(form.getExpiresAt());
        provisioningCredentialRepository.save(entity);

        IssuedCredentialDTO dto = new IssuedCredentialDTO();
        dto.setCredentialId(entity.getCredentialId());
        dto.setTenantId(entity.getTenantId());
        dto.setToken(token);
        dto.setExpiresAt(entity.getExpiresAt());
        return dto;
    }

    @Transactional
    public ClientAppDTO createClientApp(String tenantId, String createdByUserId, CreateClientAppForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(tenantId, "tenantId is required");
        requireText(form.getProvisioningToken(), "provisioningToken is required");
        requireText(form.getName(), "name is required");

        ClientAppProvisioningCredentialEntity credential = provisioningCredentialRepository
                .findByTokenHash(SecretTokenSupport.sha256(form.getProvisioningToken()))
                .orElseThrow(() -> new IllegalArgumentException("invalid provisioning credential"));
        validateProvisioningCredential(tenantId, credential);

        ClientAppEntity entity = new ClientAppEntity();
        entity.setClientAppId("capp_" + UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setName(form.getName());
        entity.setDescription(form.getDescription());
        entity.setOwnerUserId(StringUtils.hasText(form.getOwnerUserId()) ? form.getOwnerUserId() : credential.getOwnerUserId());
        entity.setCapabilityDomain(StringUtils.hasText(form.getCapabilityDomain())
                ? form.getCapabilityDomain()
                : credential.getCapabilityDomain());
        entity.setStatus(STATUS_ACTIVE);
        entity.setProvisioningCredentialId(credential.getCredentialId());
        entity.setCreatedBy(createdByUserId);
        ClientAppEntity saved = clientAppRepository.save(entity);

        credential.setUsedCount(credential.getUsedCount() + 1);
        if (credential.getUsedCount() >= credential.getMaxUses()) {
            credential.setStatus(STATUS_USED);
        }
        provisioningCredentialRepository.save(credential);
        return ClientAppDTO.fromEntity(saved);
    }

    @Transactional
    public IssuedCredentialDTO issueRuntimeCredential(String tenantId, String clientAppId, IssueRuntimeCredentialForm form) {
        ClientAppEntity app = requireActiveClientApp(tenantId, clientAppId);
        String secret = SecretTokenSupport.randomToken("cas_");

        ClientAppRuntimeCredentialEntity entity = new ClientAppRuntimeCredentialEntity();
        entity.setCredentialId("carc_" + UUID.randomUUID());
        entity.setClientAppId(app.getClientAppId());
        entity.setTenantId(app.getTenantId());
        entity.setAppKey("cak_" + UUID.randomUUID());
        entity.setSecretHash(SecretTokenSupport.sha256(secret));
        entity.setStatus(STATUS_ACTIVE);
        if (form != null) {
            entity.setDescription(form.getDescription());
            entity.setExpiresAt(form.getExpiresAt());
        }
        runtimeCredentialRepository.save(entity);

        IssuedCredentialDTO dto = new IssuedCredentialDTO();
        dto.setCredentialId(entity.getCredentialId());
        dto.setClientAppId(entity.getClientAppId());
        dto.setTenantId(entity.getTenantId());
        dto.setAppKey(entity.getAppKey());
        dto.setSecret(secret);
        dto.setExpiresAt(entity.getExpiresAt());
        return dto;
    }

    @Transactional(readOnly = true)
    public List<ClientAppDTO> listClientApps(String tenantId) {
        requireText(tenantId, "tenantId is required");
        return clientAppRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                .map(ClientAppDTO::fromEntity)
                .toList();
    }

    @Transactional(readOnly = true)
    public ClientAppEntity requireClientApp(String tenantId, String clientAppId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        return clientAppRepository.findByClientAppIdAndTenantId(clientAppId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("client app not found: " + clientAppId));
    }

    @Transactional(readOnly = true)
    public ClientAppEntity requireActiveClientApp(String tenantId, String clientAppId) {
        ClientAppEntity app = requireClientApp(tenantId, clientAppId);
        if (!STATUS_ACTIVE.equals(app.getStatus())) {
            throw new IllegalStateException("client app is not active: " + clientAppId);
        }
        return app;
    }

    @Transactional
    public ClientAppDTO updateStatus(String tenantId, String clientAppId, String status) {
        requireText(status, "status is required");
        ClientAppEntity app = requireClientApp(tenantId, clientAppId);
        app.setStatus(status);
        return ClientAppDTO.fromEntity(clientAppRepository.save(app));
    }

    private void validateProvisioningCredential(String tenantId, ClientAppProvisioningCredentialEntity credential) {
        if (!STATUS_ACTIVE.equals(credential.getStatus())) {
            throw new IllegalArgumentException("provisioning credential is not active");
        }
        if (!tenantId.equals(credential.getTenantId())) {
            throw new IllegalArgumentException("provisioning credential tenant mismatch");
        }
        if (credential.getExpiresAt() != null && credential.getExpiresAt().isBefore(LocalDateTime.now())) {
            throw new IllegalArgumentException("provisioning credential expired");
        }
        if (credential.getUsedCount() >= credential.getMaxUses()) {
            throw new IllegalArgumentException("provisioning credential used up");
        }
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
