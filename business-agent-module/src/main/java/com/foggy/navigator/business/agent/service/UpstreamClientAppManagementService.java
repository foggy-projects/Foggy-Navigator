package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamClientAppForm;
import com.foggy.navigator.business.agent.model.form.IssueControlCredentialForm;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UpstreamClientAppManagementService {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,64}");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");

    private final ClientAppRepository clientAppRepository;
    private final ClientAppService clientAppService;
    private final UpstreamClientAppAdminCredentialService adminCredentialService;

    @Transactional(readOnly = true)
    public List<ClientAppDTO> listClientApps(UpstreamClientAppAdminPrincipal principal, String tenantId) {
        requirePrincipal(principal);
        List<String> tenantIds = resolveTenantFilter(principal, tenantId);
        if (tenantIds.isEmpty()) {
            return List.of();
        }
        return clientAppRepository
                .findByUpstreamSystemIdAndUpstreamClientAppNamespaceAndTenantIdInOrderByCreatedAtDesc(
                        principal.getUpstreamSystemId(),
                        principal.getAuthorizedClientAppNamespace(),
                        tenantIds)
                .stream()
                .map(ClientAppDTO::fromEntity)
                .toList();
    }

    @Transactional
    public ClientAppDTO ensureClientApp(UpstreamClientAppAdminPrincipal principal, EnsureUpstreamClientAppForm form) {
        requirePrincipal(principal);
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String targetTenantId = requireTenantId(form.getTargetTenantId(), "targetTenantId");
        String upstreamRef = requireIdentifier(form.getUpstreamRef(), "upstreamRef");
        adminCredentialService.requireTenant(principal, targetTenantId);

        return clientAppRepository
                .findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                        targetTenantId,
                        principal.getUpstreamSystemId(),
                        principal.getAuthorizedClientAppNamespace(),
                        upstreamRef)
                .map(app -> updateExistingClientApp(app, form))
                .map(ClientAppDTO::fromEntity)
                .orElseGet(() -> ClientAppDTO.fromEntity(createClientApp(principal, form, targetTenantId, upstreamRef)));
    }

    @Transactional
    public IssuedCredentialDTO issueControlCredential(UpstreamClientAppAdminPrincipal principal,
                                                      String clientAppId,
                                                      IssueControlCredentialForm form) {
        ClientAppEntity app = requireManagedActiveClientApp(principal, clientAppId);
        return clientAppService.issueControlCredential(
                app.getTenantId(),
                "upstream-admin:" + principal.getCredentialId(),
                app.getClientAppId(),
                form);
    }

    private ClientAppEntity createClientApp(UpstreamClientAppAdminPrincipal principal,
                                            EnsureUpstreamClientAppForm form,
                                            String targetTenantId,
                                            String upstreamRef) {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setClientAppId("capp_" + java.util.UUID.randomUUID());
        entity.setTenantId(targetTenantId);
        entity.setName(defaultName(form.getName(), principal, upstreamRef));
        entity.setDescription(trimToNull(form.getDescription(), 2000));
        entity.setOwnerUserId(trimToNull(form.getOwnerUserId(), 64));
        entity.setCapabilityDomain(defaultCapabilityDomain(form.getCapabilityDomain(), principal));
        entity.setUpstreamSystemId(principal.getUpstreamSystemId());
        entity.setUpstreamClientAppNamespace(principal.getAuthorizedClientAppNamespace());
        entity.setUpstreamRef(upstreamRef);
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setCreatedBy("upstream-admin:" + principal.getCredentialId());
        return clientAppRepository.save(entity);
    }

    private ClientAppEntity updateExistingClientApp(ClientAppEntity app, EnsureUpstreamClientAppForm form) {
        if (!ClientAppService.STATUS_ACTIVE.equals(app.getStatus())) {
            throw new IllegalStateException("client app is not active: " + app.getClientAppId());
        }
        if (StringUtils.hasText(form.getName())) {
            app.setName(trimToLength(form.getName(), 128));
        }
        if (StringUtils.hasText(form.getDescription())) {
            app.setDescription(trimToNull(form.getDescription(), 2000));
        }
        if (StringUtils.hasText(form.getOwnerUserId())) {
            app.setOwnerUserId(trimToNull(form.getOwnerUserId(), 64));
        }
        if (StringUtils.hasText(form.getCapabilityDomain())) {
            app.setCapabilityDomain(trimToNull(form.getCapabilityDomain(), 64));
        }
        return clientAppRepository.save(app);
    }

    private ClientAppEntity requireManagedActiveClientApp(UpstreamClientAppAdminPrincipal principal, String clientAppId) {
        requirePrincipal(principal);
        String id = requireText(clientAppId, "clientAppId is required");
        ClientAppEntity app = clientAppRepository.findByClientAppId(id)
                .orElseThrow(() -> new IllegalArgumentException("client app not found: " + id));
        adminCredentialService.requireTenant(principal, app.getTenantId());
        if (!principal.getUpstreamSystemId().equals(app.getUpstreamSystemId())
                || !principal.getAuthorizedClientAppNamespace().equals(app.getUpstreamClientAppNamespace())) {
            throw new SecurityException("upstream admin credential clientApp mismatch");
        }
        if (!ClientAppService.STATUS_ACTIVE.equals(app.getStatus())) {
            throw new IllegalStateException("client app is not active: " + id);
        }
        return app;
    }

    private List<String> resolveTenantFilter(UpstreamClientAppAdminPrincipal principal, String tenantId) {
        if (StringUtils.hasText(tenantId)) {
            String normalized = requireTenantId(tenantId, "tenantId");
            adminCredentialService.requireTenant(principal, normalized);
            return List.of(normalized);
        }
        return principal.getAuthorizedTenantIds() == null
                ? List.of()
                : List.copyOf(principal.getAuthorizedTenantIds());
    }

    private String defaultName(String name, UpstreamClientAppAdminPrincipal principal, String upstreamRef) {
        if (StringUtils.hasText(name)) {
            return trimToLength(name, 128);
        }
        return trimToLength(principal.getAuthorizedClientAppNamespace() + ":" + upstreamRef, 128);
    }

    private String defaultCapabilityDomain(String capabilityDomain, UpstreamClientAppAdminPrincipal principal) {
        return StringUtils.hasText(capabilityDomain)
                ? trimToNull(capabilityDomain, 64)
                : trimToNull(principal.getAuthorizedClientAppNamespace(), 64);
    }

    private void requirePrincipal(UpstreamClientAppAdminPrincipal principal) {
        if (principal == null
                || !StringUtils.hasText(principal.getCredentialId())
                || !StringUtils.hasText(principal.getUpstreamSystemId())
                || !StringUtils.hasText(principal.getAuthorizedClientAppNamespace())) {
            throw new SecurityException("upstream admin credential is required");
        }
    }

    private String requireTenantId(String value, String fieldName) {
        String text = requireText(value, fieldName + " is required").trim();
        if (!TENANT_ID_PATTERN.matcher(text).matches()) {
            throw new IllegalArgumentException(fieldName + " must match [A-Za-z0-9._:-]{1,64}");
        }
        return text;
    }

    private String requireIdentifier(String value, String fieldName) {
        String text = requireText(value, fieldName + " is required").trim();
        if (!IDENTIFIER_PATTERN.matcher(text).matches()) {
            throw new IllegalArgumentException(fieldName + " must match [A-Za-z0-9._:-]{1,128}");
        }
        return text;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }

    private String trimToNull(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return trimToLength(value, maxLength);
    }

    private String trimToLength(String value, int maxLength) {
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }
}
