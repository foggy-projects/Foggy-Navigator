package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppUpstreamUserGrantDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppUpstreamUserGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantUpstreamUserForm;
import com.foggy.navigator.business.agent.repository.ClientAppUpstreamUserGrantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.UUID;

import static com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService.STATUS_DISABLED;
import static com.foggy.navigator.business.agent.service.BusinessFunctionRegistryService.STATUS_ENABLED;
import org.springframework.util.Assert;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClientAppUserGrantService {

    private final ClientAppUpstreamUserGrantRepository grantRepository;
    private final ClientAppService clientAppService;

    @Transactional
    public ClientAppUpstreamUserGrantDTO grantUpstreamUserAccess(String tenantId, String clientAppId, String actorUserId, GrantUpstreamUserForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(clientAppId, "clientAppId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getUpstreamUserId(), "upstreamUserId is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        // Validate App
        ClientAppEntity app = clientAppService.requireActiveClientApp(tenantId, clientAppId);

        ClientAppUpstreamUserGrantEntity grant = grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId(tenantId, clientAppId, form.getUpstreamUserId())
                .orElseGet(() -> {
                    ClientAppUpstreamUserGrantEntity entity = new ClientAppUpstreamUserGrantEntity();
                    entity.setGrantId(UUID.randomUUID().toString().replace("-", ""));
                    return entity;
                });

        grant.setTenantId(tenantId);
        grant.setClientAppId(clientAppId);
        grant.setUpstreamUserId(form.getUpstreamUserId());
        grant.setStatus(status);
        if (!StringUtils.hasText(grant.getCreatedBy())) {
            grant.setCreatedBy(actorUserId);
        }

        return ClientAppUpstreamUserGrantDTO.fromEntity(grantRepository.save(grant));
    }

    @Transactional
    public ClientAppUpstreamUserGrantDTO updateUpstreamUserGrantStatus(String tenantId, String clientAppId, String upstreamUserId, String status) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(clientAppId, "clientAppId is required");
        Assert.hasText(upstreamUserId, "upstreamUserId is required");
        Assert.hasText(status, "status is required");

        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        clientAppService.requireClientApp(tenantId, clientAppId);

        ClientAppUpstreamUserGrantEntity grant = grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId(tenantId, clientAppId, upstreamUserId)
                .orElseThrow(() -> new IllegalArgumentException("Grant not found"));

        grant.setStatus(status);
        return ClientAppUpstreamUserGrantDTO.fromEntity(grantRepository.save(grant));
    }

    @Transactional(readOnly = true)
    public void checkUpstreamUserAccess(String tenantId, String clientAppId, String upstreamUserId) {
        clientAppService.requireActiveClientApp(tenantId, clientAppId);

        ClientAppUpstreamUserGrantEntity grant = grantRepository.findByTenantIdAndClientAppIdAndUpstreamUserId(tenantId, clientAppId, upstreamUserId)
                .orElseThrow(() -> new IllegalStateException("Upstream user is not granted access to this Client App"));

        if (!STATUS_ENABLED.equals(grant.getStatus())) {
            throw new IllegalStateException("Upstream user grant is disabled");
        }
    }
}
