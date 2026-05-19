package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.ClientAppDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppModelConfigGrantDTO;
import com.foggy.navigator.business.agent.model.dto.IssuedCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamClientAppAdminPrincipal;
import com.foggy.navigator.business.agent.model.dto.UpstreamTenantClientAppProvisioningDTO;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.form.EnsureUpstreamTenantClientAppForm;
import com.foggy.navigator.business.agent.model.form.GrantModelConfigForm;
import com.foggy.navigator.business.agent.model.form.IssueControlCredentialForm;
import com.foggy.navigator.business.agent.model.form.IssueRuntimeCredentialForm;
import com.foggy.navigator.business.agent.model.form.SyncSkillBundleForm;
import com.foggy.navigator.business.agent.repository.BusinessCodingAgentRepository;
import com.foggy.navigator.business.agent.repository.ClientAppRepository;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UpstreamTenantClientAppProvisioningService {

    public static final String STATUS_READY = "READY";
    public static final String STATUS_CREDENTIALS_NOT_REPLAYABLE = "CREDENTIALS_NOT_REPLAYABLE";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String DEFAULT_SKILL_ID = "tms.navigator.agent";
    private static final String DEFAULT_AGENT_SUFFIX = "root-agent";

    private final ClientAppRepository clientAppRepository;
    private final ClientAppService clientAppService;
    private final ClientAppModelConfigGrantService modelConfigGrantService;
    private final BusinessCodingAgentRepository agentRepository;
    private final SkillRegistryService skillRegistryService;
    private final ObjectMapper objectMapper;

    @Transactional
    public UpstreamTenantClientAppProvisioningDTO ensure(EnsureUpstreamTenantClientAppForm form,
                                                         UpstreamClientAppAdminPrincipal principal) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String sourceSystem = requireIdentifier(form.getSourceSystem(), "sourceSystem");
        String sourceTenantId = requireIdentifier(form.getSourceTenantId(), "sourceTenantId");
        String navigatorTenantId = deriveNavigatorTenantId(sourceSystem, sourceTenantId);
        log.info("Upstream tenant ClientApp provisioning started: sourceSystem={}, sourceTenantId={}, navigatorTenantId={}, rotateCredentials={}, credentialId={}, principalUpstreamSystemId={}, authorizedNamespace={}, authorizedTenantCount={}",
                sourceSystem, sourceTenantId, navigatorTenantId, form.getRotateCredentials(),
                credentialId(principal), upstreamSystemId(principal), authorizedNamespace(principal),
                authorizedTenantCount(principal));
        requirePrincipal(principal, sourceSystem, navigatorTenantId);
        String upstreamNamespace = requireIdentifier(principal.getAuthorizedClientAppNamespace(), "authorizedClientAppNamespace");
        String actorUserId = actorUserId(principal);

        boolean appExists = clientAppRepository
                .findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                        navigatorTenantId, sourceSystem, upstreamNamespace, sourceTenantId)
                .isPresent();
        ClientAppDTO clientApp = ensureClientApp(form, sourceSystem, sourceTenantId, navigatorTenantId, upstreamNamespace, actorUserId);
        boolean created = !appExists;

        IssuedCredentialDTO runtimeCredential = null;
        IssuedCredentialDTO controlCredential = null;
        boolean shouldIssueCredentials = created || Boolean.TRUE.equals(form.getRotateCredentials());
        if (shouldIssueCredentials) {
            runtimeCredential = issueRuntimeCredential(clientApp.getTenantId(), clientApp.getClientAppId(), sourceSystem, sourceTenantId);
            controlCredential = issueControlCredential(clientApp.getTenantId(), clientApp.getClientAppId(), actorUserId, sourceSystem, sourceTenantId);
        }

        UpstreamTenantClientAppProvisioningDTO result = new UpstreamTenantClientAppProvisioningDTO();
        result.setNavigatorTenantId(clientApp.getTenantId());
        result.setClientAppId(clientApp.getClientAppId());
        result.setClientAppName(clientApp.getName());
        result.setCapabilityDomain(clientApp.getCapabilityDomain());
        result.setClientAppKey(runtimeCredential == null ? null : runtimeCredential.getAppKey());
        result.setClientAppSecret(runtimeCredential == null ? null : runtimeCredential.getSecret());
        result.setControlApiKey(controlCredential == null ? null : controlCredential.getControlApiKey());
        result.setCreated(created);
        result.setRotated(!created && Boolean.TRUE.equals(form.getRotateCredentials()));
        result.setCredentialsReplayable(shouldIssueCredentials);
        result.setStatus(shouldIssueCredentials ? STATUS_READY : STATUS_CREDENTIALS_NOT_REPLAYABLE);
        if (!shouldIssueCredentials) {
            result.setErrorCode(STATUS_CREDENTIALS_NOT_REPLAYABLE);
            result.setMessage("binding secrets are one-time credentials; call again with rotateCredentials=true to issue new credentials");
            log.info("Upstream tenant ClientApp credentials are not replayable: sourceSystem={}, sourceTenantId={}, navigatorTenantId={}, clientAppId={}, credentialId={}",
                    sourceSystem, sourceTenantId, clientApp.getTenantId(), clientApp.getClientAppId(),
                    principal.getCredentialId());
        }
        result.setWorkerPoolId(trimToNull(form.getWorkerPoolId(), 64));
        result.setBindingVersion(UUID.randomUUID().toString());

        String modelConfigId = ensureModelConfigGrant(clientApp.getTenantId(), clientApp.getClientAppId(), form, actorUserId, result.getBlockers());
        result.setModelConfigId(modelConfigId);

        String skillId = defaultText(form.getSkillId(), DEFAULT_SKILL_ID);
        String rootAgentId = ensureRootAgent(clientApp, form, actorUserId, modelConfigId, skillId, result.getBlockers());
        result.setRootAgentId(rootAgentId);
        result.setSkillId(skillId);
        ensureRootSkillBundle(clientApp, actorUserId, skillId, rootAgentId, form);
        log.info("Upstream tenant ClientApp provisioning finished: sourceSystem={}, sourceTenantId={}, navigatorTenantId={}, clientAppId={}, created={}, rotated={}, status={}, credentialsReplayable={}, modelConfigId={}, rootAgentId={}, skillId={}, blockers={}",
                sourceSystem, sourceTenantId, result.getNavigatorTenantId(), result.getClientAppId(),
                result.isCreated(), result.isRotated(), result.getStatus(), result.isCredentialsReplayable(),
                result.getModelConfigId(), result.getRootAgentId(), result.getSkillId(), result.getBlockers());
        return result;
    }

    private void requirePrincipal(UpstreamClientAppAdminPrincipal principal,
                                  String sourceSystem,
                                  String navigatorTenantId) {
        if (principal == null
                || !StringUtils.hasText(principal.getCredentialId())
                || !StringUtils.hasText(principal.getUpstreamSystemId())
                || !StringUtils.hasText(principal.getAuthorizedClientAppNamespace())) {
            log.warn("Upstream tenant ClientApp provisioning rejected: reason=admin credential principal missing, sourceSystem={}, navigatorTenantId={}, credentialId={}, principalUpstreamSystemId={}, authorizedNamespace={}, authorizedTenantCount={}",
                    sourceSystem, navigatorTenantId, credentialId(principal), upstreamSystemId(principal),
                    authorizedNamespace(principal), authorizedTenantCount(principal));
            throw new SecurityException("upstream admin credential is required");
        }
        if (!sourceSystem.equals(principal.getUpstreamSystemId())) {
            log.warn("Upstream tenant ClientApp provisioning rejected: reason=sourceSystem mismatch, sourceSystem={}, principalUpstreamSystemId={}, navigatorTenantId={}, credentialId={}",
                    sourceSystem, principal.getUpstreamSystemId(), navigatorTenantId, principal.getCredentialId());
            throw new SecurityException("upstream admin credential sourceSystem mismatch");
        }
        if (principal.getAuthorizedTenantIds() == null
                || !principal.getAuthorizedTenantIds().contains(navigatorTenantId)) {
            log.warn("Upstream tenant ClientApp provisioning rejected: reason=tenant mismatch, sourceSystem={}, navigatorTenantId={}, credentialId={}, authorizedTenantCount={}",
                    sourceSystem, navigatorTenantId, principal.getCredentialId(), authorizedTenantCount(principal));
            throw new SecurityException("upstream admin credential tenant mismatch");
        }
    }

    private String credentialId(UpstreamClientAppAdminPrincipal principal) {
        return principal == null ? null : principal.getCredentialId();
    }

    private String upstreamSystemId(UpstreamClientAppAdminPrincipal principal) {
        return principal == null ? null : principal.getUpstreamSystemId();
    }

    private String authorizedNamespace(UpstreamClientAppAdminPrincipal principal) {
        return principal == null ? null : principal.getAuthorizedClientAppNamespace();
    }

    private int authorizedTenantCount(UpstreamClientAppAdminPrincipal principal) {
        Set<String> tenantIds = principal == null ? null : principal.getAuthorizedTenantIds();
        return tenantIds == null ? 0 : tenantIds.size();
    }

    private String actorUserId(UpstreamClientAppAdminPrincipal principal) {
        return "upstream-admin:" + principal.getCredentialId();
    }

    private ClientAppDTO ensureClientApp(EnsureUpstreamTenantClientAppForm form,
                                         String sourceSystem,
                                         String sourceTenantId,
                                         String navigatorTenantId,
                                         String upstreamNamespace,
                                         String actorUserId) {
        return clientAppRepository
                .findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                        navigatorTenantId, sourceSystem, upstreamNamespace, sourceTenantId)
                .map(app -> updateExistingClientApp(app, form))
                .map(ClientAppDTO::fromEntity)
                .orElseGet(() -> ClientAppDTO.fromEntity(createClientApp(form, sourceSystem, sourceTenantId,
                        navigatorTenantId, upstreamNamespace, actorUserId)));
    }

    private ClientAppEntity createClientApp(EnsureUpstreamTenantClientAppForm form,
                                            String sourceSystem,
                                            String sourceTenantId,
                                            String navigatorTenantId,
                                            String upstreamNamespace,
                                            String actorUserId) {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setClientAppId("capp_" + UUID.randomUUID());
        entity.setTenantId(navigatorTenantId);
        entity.setName(defaultClientAppName(form, sourceSystem, sourceTenantId));
        entity.setDescription(trimToNull(form.getTenantName(), 2000));
        entity.setCapabilityDomain(defaultCapabilityDomain(form, sourceSystem, sourceTenantId));
        entity.setUpstreamSystemId(sourceSystem);
        entity.setUpstreamClientAppNamespace(upstreamNamespace);
        entity.setUpstreamRef(sourceTenantId);
        entity.setStatus(ClientAppService.STATUS_ACTIVE);
        entity.setCreatedBy(defaultText(actorUserId, "upstream-tenant-provisioning"));
        return clientAppRepository.save(entity);
    }

    private ClientAppEntity updateExistingClientApp(ClientAppEntity app, EnsureUpstreamTenantClientAppForm form) {
        if (!ClientAppService.STATUS_ACTIVE.equals(app.getStatus())) {
            throw new IllegalStateException("client app is not active: " + app.getClientAppId());
        }
        if (StringUtils.hasText(form.getClientAppName())) {
            app.setName(trimToLength(form.getClientAppName(), 128));
        }
        if (StringUtils.hasText(form.getTenantName())) {
            app.setDescription(trimToNull(form.getTenantName(), 2000));
        }
        if (StringUtils.hasText(form.getCapabilityDomain())) {
            app.setCapabilityDomain(trimToNull(form.getCapabilityDomain(), 64));
        }
        return clientAppRepository.save(app);
    }

    private IssuedCredentialDTO issueRuntimeCredential(String tenantId, String clientAppId, String sourceSystem, String sourceTenantId) {
        IssueRuntimeCredentialForm credentialForm = new IssueRuntimeCredentialForm();
        credentialForm.setDescription("upstream tenant provisioning: " + sourceSystem + "/" + sourceTenantId);
        return clientAppService.issueRuntimeCredential(tenantId, clientAppId, credentialForm);
    }

    private IssuedCredentialDTO issueControlCredential(String tenantId,
                                                       String clientAppId,
                                                       String actorUserId,
                                                       String sourceSystem,
                                                       String sourceTenantId) {
        IssueControlCredentialForm credentialForm = new IssueControlCredentialForm();
        credentialForm.setDescription("upstream tenant provisioning: " + sourceSystem + "/" + sourceTenantId);
        credentialForm.setEffectiveUserId(defaultText(actorUserId, "upstream-tenant-provisioning"));
        credentialForm.setScopes(List.copyOf(ClientAppControlCredentialService.defaultScopes()));
        return clientAppService.issueControlCredential(tenantId,
                defaultText(actorUserId, "upstream-tenant-provisioning"),
                clientAppId,
                credentialForm);
    }

    private String ensureModelConfigGrant(String tenantId,
                                          String clientAppId,
                                          EnsureUpstreamTenantClientAppForm form,
                                          String actorUserId,
                                          List<String> blockers) {
        if (StringUtils.hasText(form.getModelConfigId())) {
            String modelConfigId = form.getModelConfigId().trim();
            Optional<ClientAppModelConfigGrantDTO> existing = modelConfigGrantService.listGrants(tenantId, clientAppId).stream()
                    .filter(grant -> modelConfigId.equals(grant.getModelConfigId()))
                    .findFirst();
            if (existing.isEmpty()) {
                GrantModelConfigForm grantForm = new GrantModelConfigForm();
                grantForm.setModelConfigId(modelConfigId);
                grantForm.setIsDefault(true);
                grantForm.setGrantScope("APP");
                modelConfigGrantService.grantModelConfig(tenantId, actorUserId, clientAppId, grantForm);
            } else {
                ClientAppModelConfigGrantDTO grant = existing.get();
                if (!ClientAppModelConfigGrantService.STATUS_ENABLED.equals(grant.getStatus())) {
                    grant = modelConfigGrantService.updateStatus(
                            tenantId, clientAppId, grant.getId(), ClientAppModelConfigGrantService.STATUS_ENABLED);
                }
                if (!Boolean.TRUE.equals(grant.getIsDefault())) {
                    modelConfigGrantService.setDefault(tenantId, clientAppId, grant.getId());
                }
            }
            return modelConfigId;
        }

        Optional<ClientAppModelConfigGrantDTO> defaultGrant = modelConfigGrantService.listGrants(tenantId, clientAppId).stream()
                .filter(grant -> ClientAppModelConfigGrantService.STATUS_ENABLED.equals(grant.getStatus()))
                .filter(grant -> Boolean.TRUE.equals(grant.getIsDefault()))
                .filter(grant -> ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND.equals(grant.getWorkerBackend()))
                .findFirst();
        if (defaultGrant.isPresent()) {
            return defaultGrant.get().getModelConfigId();
        }

        String profile = StringUtils.hasText(form.getModelProfileCode()) ? form.getModelProfileCode().trim() : "default";
        blockers.add("modelConfigId is missing and no default ClientApp model grant exists for profile: " + profile);
        return null;
    }

    private String ensureRootAgent(ClientAppDTO clientApp,
                                   EnsureUpstreamTenantClientAppForm form,
                                   String actorUserId,
                                   String modelConfigId,
                                   String skillId,
                                   List<String> blockers) {
        String rootAgentId = defaultRootAgentId(form, clientApp);
        CodingAgentEntity entity = agentRepository.findByAgentIdAndTenantId(rootAgentId, clientApp.getTenantId())
                .orElseGet(CodingAgentEntity::new);
        entity.setAgentId(rootAgentId);
        entity.setUserId(defaultText(actorUserId, "upstream-tenant-provisioning"));
        entity.setTenantId(clientApp.getTenantId());
        entity.setName(defaultText(form.getTenantName(), clientApp.getName() + " root agent"));
        entity.setDescription("Upstream tenant root agent for ClientApp " + clientApp.getClientAppId());
        entity.setAgentType(BusinessAgentBundleService.AGENT_TYPE_LANGGRAPH);
        String explicitWorkerId = trimToNull(form.getWorkerPoolId(), 64);
        if (StringUtils.hasText(explicitWorkerId)) {
            entity.setWorkerId(explicitWorkerId);
        }
        entity.setDefaultModelConfigId(modelConfigId);
        entity.setSkills(buildSkillSummary(skillId, entity.getName(), entity.getDescription()));
        entity.setAgentProfile(buildAgentProfile(clientApp.getClientAppId(), skillId, form));
        agentRepository.save(entity);
        if (!StringUtils.hasText(modelConfigId)) {
            blockers.add("root agent was ensured without defaultModelConfigId; readiness will fail until a model grant is configured");
        }
        return rootAgentId;
    }

    private void ensureRootSkillBundle(ClientAppDTO clientApp,
                                       String actorUserId,
                                       String skillId,
                                       String rootAgentId,
                                       EnsureUpstreamTenantClientAppForm form) {
        SyncSkillBundleForm skillForm = new SyncSkillBundleForm();
        skillForm.setClientAppId(clientApp.getClientAppId());
        skillForm.setScope(SkillRegistryService.SCOPE_CLIENT_APP_PUBLIC);
        skillForm.setSkillId(skillId);
        skillForm.setName(defaultText(form.getTenantName(), clientApp.getName() + " root skill"));
        skillForm.setDescription("Default root skill for upstream tenant agent " + rootAgentId);
        skillForm.setStatus(BusinessFunctionRegistryService.STATUS_ENABLED);
        skillForm.setMarkdownBody("Use the current upstream tenant context and route requests through the granted business functions.");
        skillForm.setContextVisibility("isolated");
        skillRegistryService.syncSkillBundle(clientApp.getTenantId(), actorUserId, skillForm);
    }

    private String buildSkillSummary(String skillId, String name, String description) {
        try {
            return objectMapper.writeValueAsString(List.of(Map.of(
                    "id", skillId,
                    "name", name,
                    "description", description == null ? "" : description
            )));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid root agent skill summary", e);
        }
    }

    private String buildAgentProfile(String clientAppId, String skillId, EnsureUpstreamTenantClientAppForm form) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "domain", "BUSINESS_AGENT",
                    "kind", defaultText(form.getAgentRole(), "ROOT"),
                    "source", "UPSTREAM_TENANT_PROVISIONING",
                    "clientAppId", clientAppId,
                    "skillId", skillId,
                    "modelProfileCode", defaultText(form.getModelProfileCode(), "default")
            ));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("invalid root agent profile", e);
        }
    }

    private String defaultRootAgentId(EnsureUpstreamTenantClientAppForm form, ClientAppDTO clientApp) {
        if (StringUtils.hasText(form.getAgentBundleCode())) {
            return trimToLength(form.getAgentBundleCode(), 64);
        }
        return trimToLength(clientApp.getCapabilityDomain() + "-" + DEFAULT_AGENT_SUFFIX, 64);
    }

    private String defaultClientAppName(EnsureUpstreamTenantClientAppForm form, String sourceSystem, String sourceTenantId) {
        return defaultText(form.getClientAppName(),
                normalizeIdentifier(sourceSystem, 32).toLowerCase(Locale.ROOT) + "-tenant-" + normalizeIdentifier(sourceTenantId, 64));
    }

    private String defaultCapabilityDomain(EnsureUpstreamTenantClientAppForm form, String sourceSystem, String sourceTenantId) {
        return defaultText(form.getCapabilityDomain(),
                normalizeIdentifier(sourceSystem, 32).toLowerCase(Locale.ROOT) + "-tenant-" + normalizeIdentifier(sourceTenantId, 48));
    }

    private String deriveNavigatorTenantId(String sourceSystem, String sourceTenantId) {
        String candidate = "nav_" + normalizeIdentifier(sourceSystem, 24).toLowerCase(Locale.ROOT)
                + "_" + normalizeIdentifier(sourceTenantId, 48);
        if (candidate.length() <= 64) {
            return candidate;
        }
        return candidate.substring(0, 55) + "_" + Integer.toHexString(candidate.hashCode());
    }

    private String normalizeIdentifier(String value, int maxLength) {
        String normalized = value.trim().replaceAll("[^A-Za-z0-9._:-]", "-");
        return trimToLength(normalized, maxLength);
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

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value.trim() : defaultValue;
    }

    private String trimToNull(String value, int maxLength) {
        return StringUtils.hasText(value) ? trimToLength(value, maxLength) : null;
    }

    private String trimToLength(String value, int maxLength) {
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }
}
