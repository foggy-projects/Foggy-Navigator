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
import com.foggy.navigator.business.agent.transaction.ReadinessTransactional;
import com.foggy.navigator.common.entity.CodingAgentEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
    public static final String ERROR_MODEL_CONFIG_RESOURCE = "MODEL_CONFIG_RESOURCE";
    public static final String ERROR_RUNTIME_AGENT_RESOURCE = "RUNTIME_AGENT_RESOURCE";
    public static final String ERROR_WORKSPACE_RESOURCE = "WORKSPACE_RESOURCE";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final String DEFAULT_SKILL_ID = "tms.navigator.agent";
    private static final String DEFAULT_AGENT_SUFFIX = "root-agent";
    private static final String DEFAULT_TMS_WORKER_BACKEND = ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND;

    private final ClientAppRepository clientAppRepository;
    private final ClientAppService clientAppService;
    private final ClientAppModelConfigGrantService modelConfigGrantService;
    private final BusinessCodingAgentRepository agentRepository;
    private final SkillRegistryService skillRegistryService;
    private final AgentDefaultBindingService agentDefaultBindingService;
    private final A2AgentResourceResolver agentResourceResolver;
    private final ObjectMapper objectMapper;

    @ReadinessTransactional(propagation = Propagation.NOT_SUPPORTED)
    public UpstreamTenantClientAppProvisioningDTO ensure(EnsureUpstreamTenantClientAppForm form,
                                                         UpstreamClientAppAdminPrincipal principal) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String sourceSystem = requireIdentifier(form.getSourceSystem(), "sourceSystem");
        String sourceTenantId = requireIdentifier(form.getSourceTenantId(), "sourceTenantId");
        String upstreamRef = StringUtils.hasText(form.getUpstreamRef())
                ? requireIdentifier(form.getUpstreamRef(), "upstreamRef")
                : sourceTenantId;
        String navigatorTenantId = deriveNavigatorTenantId(sourceSystem, sourceTenantId);
        log.info("Upstream tenant ClientApp provisioning started: sourceSystem={}, sourceTenantId={}, navigatorTenantId={}, rotateCredentials={}, credentialId={}, principalUpstreamSystemId={}, authorizedNamespace={}, authorizedTenantCount={}",
                sourceSystem, sourceTenantId, navigatorTenantId, form.getRotateCredentials(),
                credentialId(principal), upstreamSystemId(principal), authorizedNamespace(principal),
                authorizedTenantCount(principal));
        requirePrincipal(principal, sourceSystem, sourceTenantId, navigatorTenantId);
        String upstreamNamespace = requireIdentifier(principal.getAuthorizedClientAppNamespace(), "authorizedClientAppNamespace");
        String actorUserId = actorUserId(principal);

        boolean appExists = clientAppRepository
                .findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                        navigatorTenantId, sourceSystem, upstreamNamespace, upstreamRef)
                .isPresent();
        ClientAppDTO clientApp = ensureClientApp(form, sourceSystem, sourceTenantId, navigatorTenantId, upstreamNamespace, upstreamRef, actorUserId);
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
        result.setTargetNavigatorTenantId(navigatorTenantId);
        result.setClientAppId(clientApp.getClientAppId());
        result.setClientAppName(clientApp.getName());
        result.setCapabilityDomain(clientApp.getCapabilityDomain());
        result.setClientAppCapabilityDomain(clientApp.getCapabilityDomain());
        result.setUpstreamSystemId(sourceSystem);
        result.setSourceTenantId(sourceTenantId);
        result.setUpstreamRef(upstreamRef);
        result.setUpstreamNamespace(upstreamNamespace);
        result.setClientAppKey(runtimeCredential == null ? null : runtimeCredential.getAppKey());
        result.setClientAppSecret(runtimeCredential == null ? null : runtimeCredential.getSecret());
        result.setControlApiKey(controlCredential == null ? null : controlCredential.getControlApiKey());
        result.setRequiredScopes(List.of(
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE,
                UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE));
        result.setActualScopes(principal.getScopes() == null ? List.of() : List.copyOf(principal.getScopes()));
        result.setAuthorizedTenantIds(principal.getAuthorizedTenantIds() == null
                ? List.of()
                : List.copyOf(principal.getAuthorizedTenantIds()));
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
        String workerPoolId = trimToNull(form.getWorkerPoolId(), 64);
        String physicalWorkerId = defaultText(form.getPhysicalWorkerId(), workerPoolId);
        String directoryId = trimToNull(form.getDirectoryId(), 64);
        String bizWorkerBaseUrl = trimToNull(form.getBizWorkerBaseUrl(), 512);
        result.setWorkerPoolId(workerPoolId);
        result.setPhysicalWorkerId(physicalWorkerId);
        result.setDirectoryId(directoryId);
        result.setBizWorkerBaseUrl(bizWorkerBaseUrl);
        result.setBindingVersion(UUID.randomUUID().toString());

        ModelGrantSelection modelGrant = ensureModelConfigGrant(clientApp.getTenantId(), clientApp.getClientAppId(), form, actorUserId, result);
        result.setModelConfigId(modelGrant.modelConfigId());
        result.setWorkerBackend(resolveWorkerBackend(form, modelGrant.workerBackend(), sourceSystem));

        String skillId = defaultText(form.getSkillId(), DEFAULT_SKILL_ID);
        String rootAgentId = ensureRootAgent(clientApp, form, actorUserId, modelGrant.modelConfigId(), skillId, result.getBlockers());
        result.setAgentCode(rootAgentId);
        result.setRootAgentId(rootAgentId);
        result.setSkillId(skillId);
        ensureRootSkillBundle(clientApp, actorUserId, skillId, rootAgentId, form);
        validateRuntimeAgentResource(clientApp, actorUserId, result);
        validateWorkspaceResource(clientApp, actorUserId, result);
        populateActivationReadiness(result);
        log.info("Upstream tenant ClientApp provisioning finished: sourceSystem={}, sourceTenantId={}, navigatorTenantId={}, clientAppId={}, created={}, rotated={}, status={}, credentialsReplayable={}, modelConfigId={}, rootAgentId={}, skillId={}, blockers={}",
                sourceSystem, sourceTenantId, result.getNavigatorTenantId(), result.getClientAppId(),
                result.isCreated(), result.isRotated(), result.getStatus(), result.isCredentialsReplayable(),
                result.getModelConfigId(), result.getRootAgentId(), result.getSkillId(), result.getBlockers());
        return result;
    }

    private void requirePrincipal(UpstreamClientAppAdminPrincipal principal,
                                  String sourceSystem,
                                  String sourceTenantId,
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
        if (!isTenantProvisioningAuthorized(principal, sourceSystem, sourceTenantId, navigatorTenantId)) {
            log.warn("Upstream tenant ClientApp provisioning rejected: reason=tenant mismatch, sourceSystem={}, navigatorTenantId={}, credentialId={}, authorizedTenantCount={}",
                    sourceSystem, navigatorTenantId, principal.getCredentialId(), authorizedTenantCount(principal));
            throw new SecurityException("upstream admin credential tenant mismatch");
        }
    }

    private boolean isTenantProvisioningAuthorized(UpstreamClientAppAdminPrincipal principal,
                                                   String sourceSystem,
                                                   String sourceTenantId,
                                                   String navigatorTenantId) {
        Set<String> authorizedTenantIds = principal == null ? null : principal.getAuthorizedTenantIds();
        if (authorizedTenantIds == null || authorizedTenantIds.isEmpty()) {
            return false;
        }
        // ensure-tenant accepts aggregate upstream bootstrap credentials as well
        // as the legacy exact Navigator tenant scope.
        return authorizedTenantIds.contains(navigatorTenantId)
                || authorizedTenantIds.contains(sourceTenantId)
                || authorizedTenantIds.contains(sourceSystem);
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
                                         String upstreamRef,
                                         String actorUserId) {
        return clientAppRepository
                .findByTenantIdAndUpstreamSystemIdAndUpstreamClientAppNamespaceAndUpstreamRef(
                        navigatorTenantId, sourceSystem, upstreamNamespace, upstreamRef)
                .map(app -> updateExistingClientApp(app, form))
                .map(ClientAppDTO::fromEntity)
                .orElseGet(() -> ClientAppDTO.fromEntity(createClientApp(form, sourceSystem, sourceTenantId,
                        navigatorTenantId, upstreamNamespace, upstreamRef, actorUserId)));
    }

    private ClientAppEntity createClientApp(EnsureUpstreamTenantClientAppForm form,
                                            String sourceSystem,
                                            String sourceTenantId,
                                            String navigatorTenantId,
                                            String upstreamNamespace,
                                            String upstreamRef,
                                            String actorUserId) {
        ClientAppEntity entity = new ClientAppEntity();
        entity.setClientAppId("capp_" + UUID.randomUUID());
        entity.setTenantId(navigatorTenantId);
        entity.setName(defaultClientAppName(form, sourceSystem, sourceTenantId));
        entity.setDescription(trimToNull(form.getTenantName(), 2000));
        entity.setCapabilityDomain(defaultCapabilityDomain(form, sourceSystem, sourceTenantId));
        entity.setUpstreamSystemId(sourceSystem);
        entity.setUpstreamClientAppNamespace(upstreamNamespace);
        entity.setUpstreamRef(upstreamRef);
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

    private ModelGrantSelection ensureModelConfigGrant(String tenantId,
                                                       String clientAppId,
                                                       EnsureUpstreamTenantClientAppForm form,
                                                       String actorUserId,
                                                       UpstreamTenantClientAppProvisioningDTO result) {
        if (StringUtils.hasText(form.getModelConfigId())) {
            String modelConfigId = form.getModelConfigId().trim();
            try {
                Optional<ClientAppModelConfigGrantDTO> existing = modelConfigGrantService.listGrants(tenantId, clientAppId).stream()
                        .filter(grant -> modelConfigId.equals(grant.getModelConfigId()))
                        .findFirst();
                ClientAppModelConfigGrantDTO ensuredGrant;
                if (existing.isEmpty()) {
                    GrantModelConfigForm grantForm = new GrantModelConfigForm();
                    grantForm.setModelConfigId(modelConfigId);
                    grantForm.setIsDefault(true);
                    grantForm.setGrantScope("APP");
                    ensuredGrant = modelConfigGrantService.grantModelConfig(tenantId, actorUserId, clientAppId, grantForm);
                } else {
                    ClientAppModelConfigGrantDTO grant = existing.get();
                    if (!ClientAppModelConfigGrantService.STATUS_ENABLED.equals(grant.getStatus())) {
                        grant = modelConfigGrantService.updateStatus(
                                tenantId, clientAppId, grant.getId(), ClientAppModelConfigGrantService.STATUS_ENABLED);
                    }
                    if (!Boolean.TRUE.equals(grant.getIsDefault())) {
                        modelConfigGrantService.setDefault(tenantId, clientAppId, grant.getId());
                    }
                    ensuredGrant = grant;
                }
                return new ModelGrantSelection(modelConfigId, ensuredGrant == null ? null : ensuredGrant.getWorkerBackend());
            } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                String message = e.getMessage();
                String detail = StringUtils.hasText(message) ? message : e.getClass().getSimpleName();
                markModelConfigResourceNotReady(result, detail);
                return new ModelGrantSelection(null, null);
            }
        }

        Optional<ClientAppModelConfigGrantDTO> defaultGrant = modelConfigGrantService.listGrants(tenantId, clientAppId).stream()
                .filter(grant -> ClientAppModelConfigGrantService.STATUS_ENABLED.equals(grant.getStatus()))
                .filter(grant -> Boolean.TRUE.equals(grant.getIsDefault()))
                .filter(grant -> {
                    String backend = grant.getWorkerBackend();
                    return ClientAppModelConfigGrantService.LANGGRAPH_BIZ_BACKEND.equals(backend)
                            || "CLAUDE_CODE".equals(backend)
                            || "OPENAI_CODEX".equals(backend)
                            || "GEMINI_CLI".equals(backend);
                })
                .findFirst();
        if (defaultGrant.isPresent()) {
            ClientAppModelConfigGrantDTO grant = defaultGrant.get();
            return new ModelGrantSelection(grant.getModelConfigId(), grant.getWorkerBackend());
        }

        String profile = StringUtils.hasText(form.getModelProfileCode()) ? form.getModelProfileCode().trim() : "default";
        markModelConfigResourceNotReady(result,
                "modelConfigId is missing and no default ClientApp model grant exists for profile: " + profile);
        return new ModelGrantSelection(null, null);
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
        entity.setOwnerType(ResourceOwnerType.CLIENT_APP);
        entity.setOwnerId(clientApp.getClientAppId());
        entity.setClientAppId(clientApp.getClientAppId());
        entity.setName(defaultText(form.getTenantName(), clientApp.getName() + " root agent"));
        entity.setDescription("Upstream tenant root agent for ClientApp " + clientApp.getClientAppId());
        entity.setAgentType(BusinessAgentBundleService.AGENT_TYPE_LANGGRAPH);
        String explicitWorkerId = trimToNull(defaultText(form.getPhysicalWorkerId(), form.getWorkerPoolId()), 64);
        if (StringUtils.hasText(explicitWorkerId)) {
            entity.setWorkerId(explicitWorkerId);
        }
        String explicitDirectoryId = trimToNull(form.getDirectoryId(), 64);
        if (StringUtils.hasText(explicitDirectoryId)) {
            entity.setDefaultDirectoryId(explicitDirectoryId);
        }
        entity.setDefaultModelConfigId(modelConfigId);
        entity.setSkills(buildSkillSummary(skillId, entity.getName(), entity.getDescription()));
        entity.setAgentProfile(buildAgentProfile(clientApp.getClientAppId(), skillId, form));
        entity.setEnabled(true);
        CodingAgentEntity saved = agentRepository.save(entity);
        agentDefaultBindingService.ensureDefaults(saved);
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

    private String resolveWorkerBackend(EnsureUpstreamTenantClientAppForm form,
                                        String modelWorkerBackend,
                                        String sourceSystem) {
        String explicit = ClientAppModelConfigGrantService.normalizeWorkerBackend(form.getWorkerBackend());
        if (StringUtils.hasText(explicit)) {
            return explicit;
        }
        String fromGrant = ClientAppModelConfigGrantService.normalizeWorkerBackend(modelWorkerBackend);
        if (StringUtils.hasText(fromGrant)) {
            return fromGrant;
        }
        if ("TMS".equalsIgnoreCase(sourceSystem)) {
            return DEFAULT_TMS_WORKER_BACKEND;
        }
        return null;
    }

    private void populateActivationReadiness(UpstreamTenantClientAppProvisioningDTO result) {
        requireActivationField(result, "navigatorTenantId", result.getNavigatorTenantId());
        requireActivationField(result, "clientAppId", result.getClientAppId());
        requireActivationField(result, "clientAppName", result.getClientAppName());
        requireActivationField(result, "clientAppCapabilityDomain", result.getClientAppCapabilityDomain());
        requireActivationField(result, "upstreamRef", result.getUpstreamRef());
        requireActivationField(result, "runtimeCredential.clientAppKey", result.getClientAppKey());
        requireActivationField(result, "runtimeCredential.clientAppSecret", result.getClientAppSecret());
        requireActivationField(result, "controlCredential.controlApiKey", result.getControlApiKey());
        requireActivationField(result, "agentCode", result.getAgentCode());
        requireActivationField(result, "rootAgentId", result.getRootAgentId());
        requireActivationField(result, "skillId", result.getSkillId());
        requireActivationField(result, "modelConfigId", result.getModelConfigId());
        requireActivationField(result, "workerBackend", result.getWorkerBackend());
        requireActivationField(result, "physicalWorkerId", result.getPhysicalWorkerId());
        requireActivationField(result, "directoryId", result.getDirectoryId());
        result.setActivationReady(result.getMissingFields().isEmpty());
        if (!result.isActivationReady() && !StringUtils.hasText(result.getRemediationHint())) {
            result.setRemediationHint("rerun ensure-tenant with rotateCredentials=true and provide missing activation policy fields");
        }
    }

    private void validateRuntimeAgentResource(ClientAppDTO clientApp,
                                              String actorUserId,
                                              UpstreamTenantClientAppProvisioningDTO result) {
        if (!StringUtils.hasText(result.getRootAgentId()) || !StringUtils.hasText(result.getPhysicalWorkerId())) {
            return;
        }
        try {
            agentResourceResolver.resolveRequiredAgent(
                    clientApp.getTenantId(),
                    clientApp.getClientAppId(),
                    actorUserId,
                    result.getRootAgentId());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            String message = e.getMessage();
            String detail = StringUtils.hasText(message) ? message : e.getClass().getSimpleName();
            result.getBlockers().add("runtime agent resource is not activation-ready: " + detail);
            if (!StringUtils.hasText(result.getErrorCode())) {
                result.setErrorCode(ERROR_RUNTIME_AGENT_RESOURCE);
            }
            addMissingField(result, classifyRuntimeAgentResourceField(detail));
            if (!StringUtils.hasText(result.getRemediationHint())) {
                result.setRemediationHint("configure physical worker owner for " + result.getPhysicalWorkerId()
                        + ": use ownerType=PLATFORM ownerId=platform for shared runtime infrastructure, or ownerType=UPSTREAM_SYSTEM ownerId="
                        + result.getUpstreamSystemId()
                        + " for an upstream-system dedicated worker; then rerun ensure-tenant with rotateCredentials=true");
            }
        }
    }

    private void validateWorkspaceResource(ClientAppDTO clientApp,
                                           String actorUserId,
                                           UpstreamTenantClientAppProvisioningDTO result) {
        if (ERROR_RUNTIME_AGENT_RESOURCE.equals(result.getErrorCode())
                || !StringUtils.hasText(result.getRootAgentId())
                || !StringUtils.hasText(result.getDirectoryId())) {
            return;
        }
        try {
            A2AgentResourceResolver.ResolvedAgentResource agentResource = agentResourceResolver.resolveRequiredAgent(
                    clientApp.getTenantId(),
                    clientApp.getClientAppId(),
                    actorUserId,
                    result.getRootAgentId());
            agentResourceResolver.resolveRequiredWorkspaceForAgent(
                    clientApp.getTenantId(),
                    clientApp.getClientAppId(),
                    actorUserId,
                    agentResource,
                    result.getDirectoryId());
        } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
            String message = e.getMessage();
            String detail = StringUtils.hasText(message) ? message : e.getClass().getSimpleName();
            result.getBlockers().add("workspace resource is not activation-ready: " + detail);
            if (!StringUtils.hasText(result.getErrorCode())) {
                result.setErrorCode(ERROR_WORKSPACE_RESOURCE);
            }
            addMissingField(result, classifyWorkspaceResourceField(detail));
            if (!StringUtils.hasText(result.getRemediationHint())) {
                result.setRemediationHint(buildWorkspaceRemediationHint(result, detail));
            }
        }
    }

    private String classifyRuntimeAgentResourceField(String detail) {
        if (detail != null && detail.contains("owner is not configured")) {
            return "physicalWorker.owner";
        }
        if (detail != null && detail.contains("backend is not configured")) {
            return "physicalWorker.workerBackend";
        }
        if (detail != null && detail.contains("not healthy")) {
            return "physicalWorker.healthStatus";
        }
        if (detail != null && detail.contains("disabled")) {
            return "physicalWorker.status";
        }
        if (detail != null && detail.contains("not visible")) {
            return "physicalWorker.visibility";
        }
        return "runtimeAgentResource";
    }

    private void markModelConfigResourceNotReady(UpstreamTenantClientAppProvisioningDTO result, String detail) {
        result.getBlockers().add("model config resource is not activation-ready: " + detail);
        if (!StringUtils.hasText(result.getErrorCode())) {
            result.setErrorCode(ERROR_MODEL_CONFIG_RESOURCE);
        }
        addMissingField(result, classifyModelConfigResourceField(detail));
        if (!StringUtils.hasText(result.getRemediationHint())) {
            result.setRemediationHint(buildModelConfigRemediationHint(result));
        }
    }

    private String classifyModelConfigResourceField(String detail) {
        String normalized = detail == null ? "" : detail.toLowerCase(Locale.ROOT);
        if (normalized.contains("not visible")) {
            return "modelConfig.visibility";
        }
        if (normalized.contains("tenant mismatch")) {
            return "modelConfig.tenant";
        }
        if (normalized.contains("disabled")) {
            return "modelConfig.status";
        }
        if (normalized.contains("worker backend")) {
            return "modelConfig.workerBackend";
        }
        return "modelConfigId";
    }

    private String buildModelConfigRemediationHint(UpstreamTenantClientAppProvisioningDTO result) {
        return "grant a model config owned by PLATFORM/platform, UPSTREAM_SYSTEM/"
                + result.getUpstreamSystemId()
                + ", or CLIENT_APP/"
                + result.getClientAppId()
                + " for navigator tenant "
                + result.getNavigatorTenantId()
                + "; if this is a legacy model owner mismatch, repair model config owner/visibility through Navigator operator/super-admin, then rerun ensure-tenant with rotateCredentials=true";
    }

    private String classifyWorkspaceResourceField(String detail) {
        if (detail != null && detail.contains("tenant mismatch")) {
            return "directory.tenant";
        }
        if (detail != null && detail.contains("owner is not configured")) {
            return "directory.owner";
        }
        if (detail != null && detail.contains("workspaceScope is not configured")) {
            return "directory.workspaceScope";
        }
        if (detail != null && detail.contains("disabled")) {
            return "directory.status";
        }
        if (detail != null && detail.contains("not bound to agent")) {
            return "directory.agentBinding";
        }
        if (detail != null && detail.contains("not found")) {
            return "directoryId";
        }
        return "workspaceResource";
    }

    private String buildWorkspaceRemediationHint(UpstreamTenantClientAppProvisioningDTO result, String detail) {
        if (detail != null && detail.contains("tenant mismatch")) {
            return "provide or create a working directory owned by navigator tenant "
                    + result.getNavigatorTenantId()
                    + " and bind it to rootAgentId="
                    + result.getRootAgentId()
                    + ", or migrate directory "
                    + result.getDirectoryId()
                    + " to that tenant before rerunning ensure-tenant with rotateCredentials=true";
        }
        return "repair working directory "
                + result.getDirectoryId()
                + " for navigator tenant "
                + result.getNavigatorTenantId()
                + " and rootAgentId="
                + result.getRootAgentId()
                + ", then rerun ensure-tenant with rotateCredentials=true";
    }

    private void addMissingField(UpstreamTenantClientAppProvisioningDTO result, String fieldName) {
        if (!result.getMissingFields().contains(fieldName)) {
            result.getMissingFields().add(fieldName);
        }
    }

    private void requireActivationField(UpstreamTenantClientAppProvisioningDTO result,
                                        String fieldName,
                                        String value) {
        if (!StringUtils.hasText(value)) {
            addMissingField(result, fieldName);
        }
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
        if (StringUtils.hasText(form.getAgentCode())) {
            return trimToLength(form.getAgentCode(), 64);
        }
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

    private record ModelGrantSelection(String modelConfigId, String workerBackend) {
    }
}
