package com.foggy.navigator.business.agent.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialClaimDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamAdminCredentialDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapApprovalActor;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestCreatedDTO;
import com.foggy.navigator.business.agent.model.dto.UpstreamBootstrapRequestDTO;
import com.foggy.navigator.business.agent.model.entity.UpstreamBootstrapAuditEntity;
import com.foggy.navigator.business.agent.model.entity.UpstreamBootstrapRequestEntity;
import com.foggy.navigator.business.agent.model.entity.UpstreamClientAppAdminCredentialEntity;
import com.foggy.navigator.business.agent.model.form.ApproveUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.ClaimUpstreamAdminCredentialForm;
import com.foggy.navigator.business.agent.model.form.CreateUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.DenyUpstreamBootstrapRequestForm;
import com.foggy.navigator.business.agent.model.form.RotateUpstreamAdminCredentialForm;
import com.foggy.navigator.business.agent.repository.UpstreamBootstrapAuditRepository;
import com.foggy.navigator.business.agent.repository.UpstreamBootstrapRequestRepository;
import com.foggy.navigator.business.agent.repository.UpstreamClientAppAdminCredentialRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class UpstreamBootstrapRequestService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_DENIED = "DENIED";
    public static final String STATUS_EXPIRED = "EXPIRED";
    public static final String STATUS_CONSUMED = "CONSUMED";

    public static final String CREDENTIAL_STATUS_ACTIVE = "ACTIVE";
    public static final String CREDENTIAL_STATUS_REVOKED = "REVOKED";
    public static final String SCOPE_CLIENT_APP_MANAGE = "CLIENT_APP_MANAGE";
    public static final String SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE = "CLIENT_APP_CONTROL_KEY_ISSUE";
    public static final String SCOPE_CLIENT_APP_ADMIN = "CLIENT_APP_ADMIN";
    public static final String SCOPE_CONTROL_KEY_ISSUE = "CONTROL_KEY_ISSUE";
    public static final String SCOPE_WORKER_MANAGE = "WORKER_MANAGE";
    public static final String SCOPE_WORKING_DIRECTORY_MANAGE = "WORKING_DIRECTORY_MANAGE";
    public static final String SCOPE_WORKER_POOL_MANAGE = "WORKER_POOL_MANAGE";
    public static final String SCOPE_MODEL_CONFIG_MANAGE = "MODEL_CONFIG_MANAGE";
    public static final String SCOPE_AGENT_BUNDLE_SYNC = "AGENT_BUNDLE_SYNC";

    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final long DEFAULT_REQUEST_TTL_MINUTES = 24 * 60;
    private static final long DEFAULT_CLAIM_TTL_MINUTES = 24 * 60;
    public static final long DEFAULT_ADMIN_CREDENTIAL_TTL_MINUTES = 24 * 60;
    private static final long MAX_CLAIM_TTL_MINUTES = 7 * 24 * 60;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final UpstreamBootstrapRequestRepository requestRepository;
    private final UpstreamClientAppAdminCredentialRepository adminCredentialRepository;
    private final UpstreamBootstrapAuditRepository auditRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public UpstreamBootstrapRequestCreatedDTO createRequest(CreateUpstreamBootstrapRequestForm form, String sourceIp) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String upstreamSystemId = requireIdentifier(form.getUpstreamSystemId(), "upstreamSystemId");
        String requestedTenantId = requireIdentifier(form.getRequestedTenantId(), "requestedTenantId");
        String requestCode = SecretTokenSupport.randomToken("nabr_");
        String claimToken = SecretTokenSupport.randomToken("nabt_");

        UpstreamBootstrapRequestEntity entity = new UpstreamBootstrapRequestEntity();
        entity.setRequestId("ubreq_" + UUID.randomUUID());
        entity.setRequestCodeHash(SecretTokenSupport.sha256(requestCode));
        entity.setRequestCodeSuffix(suffix(requestCode));
        entity.setClaimTokenHash(SecretTokenSupport.sha256(claimToken));
        entity.setUpstreamSystemId(upstreamSystemId);
        entity.setRequestedTenantId(requestedTenantId);
        entity.setMultiTenant(Boolean.TRUE.equals(form.getMultiTenant()));
        entity.setReason(trimToNull(form.getReason(), 2000));
        entity.setApplicantLabel(trimToNull(form.getApplicantLabel(), 128));
        entity.setSourceIpHash(StringUtils.hasText(sourceIp) ? SecretTokenSupport.sha256(sourceIp) : null);
        entity.setStatus(STATUS_PENDING);
        entity.setRequestExpiresAt(LocalDateTime.now().plusMinutes(DEFAULT_REQUEST_TTL_MINUTES));
        requestRepository.save(entity);
        recordAudit(entity, "REQUESTED", "UPSTREAM_ANONYMOUS", upstreamSystemId, null);

        UpstreamBootstrapRequestCreatedDTO dto = new UpstreamBootstrapRequestCreatedDTO();
        dto.setRequestCode(requestCode);
        dto.setRequestCodeSuffix(entity.getRequestCodeSuffix());
        dto.setClaimToken(claimToken);
        dto.setStatus(entity.getStatus());
        dto.setRequestExpiresAt(entity.getRequestExpiresAt());
        return dto;
    }

    @Transactional
    public UpstreamBootstrapRequestDTO getPublicStatus(String requestCode) {
        UpstreamBootstrapRequestEntity entity = requireRequestByCode(requestCode, false);
        refreshExpired(entity, LocalDateTime.now());
        requestRepository.save(entity);
        return toDTO(entity, false);
    }

    @Transactional
    public List<UpstreamBootstrapRequestDTO> listRequests(String status, UpstreamBootstrapApprovalActor actor) {
        requireActor(actor);
        String normalizedStatus = normalizeStatus(status);
        List<UpstreamBootstrapRequestEntity> requests = actor.isOperator() || actor.isSuperAdmin()
                ? requestRepository.findTop100ByOrderByCreatedAtDesc()
                : requestRepository.findTop100ByRequestedTenantIdOrderByCreatedAtDesc(requireActorTenant(actor));
        LocalDateTime now = LocalDateTime.now();
        List<UpstreamBootstrapRequestDTO> result = new ArrayList<>();
        for (UpstreamBootstrapRequestEntity request : requests) {
            refreshExpired(request, now);
            requestRepository.save(request);
            if (!StringUtils.hasText(normalizedStatus) || normalizedStatus.equals(request.getStatus())) {
                result.add(toDTO(request, true));
            }
        }
        return result;
    }

    @Transactional
    public UpstreamBootstrapRequestDTO getRequest(String requestCode, UpstreamBootstrapApprovalActor actor) {
        requireActor(actor);
        UpstreamBootstrapRequestEntity entity = requireRequestByCode(requestCode, false);
        refreshExpired(entity, LocalDateTime.now());
        requestRepository.save(entity);
        requireActorCanManage(actor, entity);
        return toDTO(entity, true);
    }

    @Transactional
    public UpstreamBootstrapRequestDTO approve(String requestCode, ApproveUpstreamBootstrapRequestForm form,
                                               UpstreamBootstrapApprovalActor actor) {
        requireActor(actor);
        UpstreamBootstrapRequestEntity entity = requireRequestByCode(requestCode, true);
        refreshExpired(entity, LocalDateTime.now());
        requireActorCanManage(actor, entity);
        if (!STATUS_PENDING.equals(entity.getStatus())) {
            throw new IllegalArgumentException("request is not pending");
        }

        List<String> authorizedTenantIds = normalizeAuthorizedTenantIds(form, entity);
        requireActorCanIssueTenants(actor, authorizedTenantIds);
        if (!Boolean.TRUE.equals(entity.getMultiTenant()) && authorizedTenantIds.size() > 1) {
            throw new IllegalArgumentException("single-tenant request cannot authorize multiple tenants");
        }

        LocalDateTime now = LocalDateTime.now();
        entity.setStatus(STATUS_APPROVED);
        entity.setApprovedAt(now);
        entity.setApprovedByUserId(actor.getUserId());
        entity.setApprovedByOperatorCredentialId(actor.getOperatorCredentialId());
        entity.setAuthorizedTenantIdsJson(toJson(authorizedTenantIds));
        entity.setAuthorizedClientAppNamespace(normalizeNamespace(form, entity));
        entity.setScopesJson(toJson(normalizeScopes(form == null ? null : form.getScopes())));
        entity.setAdminCredentialExpiresAt(resolveAdminCredentialExpiresAt(form, now));
        validateCredentialExpiry(entity.getAdminCredentialExpiresAt(), now);
        entity.setClaimExpiresAt(now.plusMinutes(resolveClaimTtlMinutes(form)));
        requestRepository.save(entity);
        recordAudit(entity, "APPROVED", actorType(actor), actorId(actor), null);
        return toDTO(entity, true);
    }

    @Transactional
    public UpstreamBootstrapRequestDTO deny(String requestCode, DenyUpstreamBootstrapRequestForm form,
                                            UpstreamBootstrapApprovalActor actor) {
        requireActor(actor);
        UpstreamBootstrapRequestEntity entity = requireRequestByCode(requestCode, true);
        refreshExpired(entity, LocalDateTime.now());
        requireActorCanManage(actor, entity);
        if (!STATUS_PENDING.equals(entity.getStatus())) {
            throw new IllegalArgumentException("request is not pending");
        }
        entity.setStatus(STATUS_DENIED);
        entity.setDeniedAt(LocalDateTime.now());
        entity.setDeniedReason(trimToNull(form == null ? null : form.getDeniedReason(), 2000));
        requestRepository.save(entity);
        recordAudit(entity, "DENIED", actorType(actor), actorId(actor), entity.getDeniedReason());
        return toDTO(entity, true);
    }

    @Transactional
    public UpstreamAdminCredentialClaimDTO claim(String requestCode, ClaimUpstreamAdminCredentialForm form) {
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        String claimToken = requireText(form.getClaimToken(), "claimToken is required");
        UpstreamBootstrapRequestEntity entity = requireRequestByCode(requestCode, true);
        refreshExpired(entity, LocalDateTime.now());
        if (!STATUS_APPROVED.equals(entity.getStatus())) {
            throwClaimStateException(entity);
        }
        if (!constantTimeEquals(SecretTokenSupport.sha256(claimToken), entity.getClaimTokenHash())) {
            throw new SecurityException("invalid claim token");
        }

        String adminApiKey = SecretTokenSupport.randomToken("naa_");
        UpstreamClientAppAdminCredentialEntity credential = new UpstreamClientAppAdminCredentialEntity();
        credential.setCredentialId("ucaac_" + UUID.randomUUID());
        credential.setCredentialKeyHash(SecretTokenSupport.sha256(adminApiKey));
        credential.setCredentialKeyPrefix(prefix(adminApiKey));
        credential.setCredentialKeySuffix(suffix(adminApiKey));
        credential.setUpstreamSystemId(entity.getUpstreamSystemId());
        credential.setAuthorizedTenantIdsJson(entity.getAuthorizedTenantIdsJson());
        credential.setAuthorizedClientAppNamespace(entity.getAuthorizedClientAppNamespace());
        credential.setScopesJson(entity.getScopesJson());
        credential.setStatus(CREDENTIAL_STATUS_ACTIVE);
        credential.setExpiresAt(entity.getAdminCredentialExpiresAt());
        credential.setSourceRequestId(entity.getRequestId());
        credential.setIssuedByUserId(entity.getApprovedByUserId());
        credential.setIssuedByOperatorCredentialId(entity.getApprovedByOperatorCredentialId());
        adminCredentialRepository.save(credential);

        entity.setStatus(STATUS_CONSUMED);
        entity.setConsumedAt(LocalDateTime.now());
        requestRepository.save(entity);
        recordAudit(entity, "CLAIMED", "UPSTREAM_ANONYMOUS", entity.getUpstreamSystemId(), null);

        UpstreamAdminCredentialClaimDTO dto = new UpstreamAdminCredentialClaimDTO();
        dto.setCredentialId(credential.getCredentialId());
        dto.setNaviAdminApiKey(adminApiKey);
        dto.setUpstreamSystemId(credential.getUpstreamSystemId());
        dto.setAuthorizedTenantIds(fromJson(credential.getAuthorizedTenantIdsJson()));
        dto.setAuthorizedClientAppNamespace(credential.getAuthorizedClientAppNamespace());
        dto.setScopes(fromJson(credential.getScopesJson()));
        dto.setExpiresAt(credential.getExpiresAt());
        return dto;
    }

    @Transactional
    public UpstreamAdminCredentialDTO revokeAdminCredential(String credentialId, UpstreamBootstrapApprovalActor actor) {
        requireActor(actor);
        UpstreamClientAppAdminCredentialEntity credential = requireCredentialByIdForUpdate(credentialId);
        UpstreamBootstrapRequestEntity request = requireCredentialRequest(credential, actor);

        LocalDateTime now = LocalDateTime.now();
        if (!CREDENTIAL_STATUS_REVOKED.equals(credential.getStatus())) {
            credential.setStatus(CREDENTIAL_STATUS_REVOKED);
            credential.setRevokedAt(now);
            adminCredentialRepository.save(credential);
            recordAudit(request, "ADMIN_CREDENTIAL_REVOKED", actorType(actor), actorId(actor),
                    "credentialId=" + credential.getCredentialId());
        }
        return toAdminCredentialDTO(credential);
    }

    @Transactional
    public UpstreamAdminCredentialClaimDTO rotateAdminCredential(String credentialId,
                                                                RotateUpstreamAdminCredentialForm form,
                                                                UpstreamBootstrapApprovalActor actor) {
        requireActor(actor);
        UpstreamClientAppAdminCredentialEntity oldCredential = requireCredentialByIdForUpdate(credentialId);
        UpstreamBootstrapRequestEntity request = requireCredentialRequest(oldCredential, actor);
        validateRotatableCredential(oldCredential);

        LocalDateTime now = LocalDateTime.now();
        oldCredential.setStatus(CREDENTIAL_STATUS_REVOKED);
        oldCredential.setRevokedAt(now);
        adminCredentialRepository.save(oldCredential);

        String adminApiKey = SecretTokenSupport.randomToken("naa_");
        UpstreamClientAppAdminCredentialEntity newCredential = new UpstreamClientAppAdminCredentialEntity();
        newCredential.setCredentialId("ucaac_" + UUID.randomUUID());
        newCredential.setCredentialKeyHash(SecretTokenSupport.sha256(adminApiKey));
        newCredential.setCredentialKeyPrefix(prefix(adminApiKey));
        newCredential.setCredentialKeySuffix(suffix(adminApiKey));
        newCredential.setUpstreamSystemId(oldCredential.getUpstreamSystemId());
        newCredential.setAuthorizedTenantIdsJson(oldCredential.getAuthorizedTenantIdsJson());
        newCredential.setAuthorizedClientAppNamespace(oldCredential.getAuthorizedClientAppNamespace());
        newCredential.setScopesJson(oldCredential.getScopesJson());
        newCredential.setStatus(CREDENTIAL_STATUS_ACTIVE);
        newCredential.setExpiresAt(resolveRotatedCredentialExpiresAt(oldCredential, form, now));
        validateCredentialExpiry(newCredential.getExpiresAt(), now);
        newCredential.setSourceRequestId(oldCredential.getSourceRequestId());
        newCredential.setIssuedByUserId(actor.isOperator() ? oldCredential.getIssuedByUserId() : actor.getUserId());
        newCredential.setIssuedByOperatorCredentialId(actor.isOperator()
                ? actor.getOperatorCredentialId()
                : oldCredential.getIssuedByOperatorCredentialId());
        adminCredentialRepository.save(newCredential);
        recordAudit(request, "ADMIN_CREDENTIAL_ROTATED", actorType(actor), actorId(actor),
                "oldCredentialId=" + oldCredential.getCredentialId()
                        + ";newCredentialId=" + newCredential.getCredentialId());

        return toClaimDTO(newCredential, adminApiKey);
    }

    private UpstreamBootstrapRequestEntity requireRequestByCode(String requestCode, boolean forUpdate) {
        String code = requireText(requestCode, "requestCode is required");
        String hash = SecretTokenSupport.sha256(code);
        return (forUpdate
                ? requestRepository.findByRequestCodeHashForUpdate(hash)
                : requestRepository.findByRequestCodeHash(hash))
                .orElseThrow(() -> new IllegalArgumentException("request not found"));
    }

    private void refreshExpired(UpstreamBootstrapRequestEntity entity, LocalDateTime now) {
        if (STATUS_PENDING.equals(entity.getStatus())
                && entity.getRequestExpiresAt() != null
                && !now.isBefore(entity.getRequestExpiresAt())) {
            entity.setStatus(STATUS_EXPIRED);
            recordAudit(entity, "EXPIRED", "SYSTEM", "ttl", null);
            return;
        }
        if (STATUS_APPROVED.equals(entity.getStatus())
                && entity.getClaimExpiresAt() != null
                && !now.isBefore(entity.getClaimExpiresAt())) {
            entity.setStatus(STATUS_EXPIRED);
            recordAudit(entity, "EXPIRED", "SYSTEM", "claim-ttl", null);
        }
    }

    private void requireActor(UpstreamBootstrapApprovalActor actor) {
        if (actor == null || (!actor.isOperator() && !actor.isTenantAdmin() && !actor.isSuperAdmin())) {
            throw new SecurityException("navigator admin or operator credential is required");
        }
    }

    private void requireActorCanManage(UpstreamBootstrapApprovalActor actor, UpstreamBootstrapRequestEntity entity) {
        if (actor.isOperator() || actor.isSuperAdmin()) {
            return;
        }
        if (actor.isTenantAdmin() && entity.getRequestedTenantId().equals(actor.getTenantId())) {
            return;
        }
        throw new SecurityException("upstream bootstrap request tenant mismatch");
    }

    private void requireActorCanIssueTenants(UpstreamBootstrapApprovalActor actor, List<String> tenantIds) {
        if (actor.isOperator() || actor.isSuperAdmin()) {
            return;
        }
        String actorTenantId = requireActorTenant(actor);
        boolean sameTenantOnly = tenantIds.stream().allMatch(actorTenantId::equals);
        if (!sameTenantOnly) {
            throw new SecurityException("authorized tenant mismatch");
        }
    }

    private String requireActorTenant(UpstreamBootstrapApprovalActor actor) {
        if (!StringUtils.hasText(actor.getTenantId())) {
            throw new SecurityException("tenant admin tenantId is required");
        }
        return actor.getTenantId();
    }

    private List<String> normalizeAuthorizedTenantIds(ApproveUpstreamBootstrapRequestForm form,
                                                      UpstreamBootstrapRequestEntity entity) {
        List<String> values = form == null ? null : form.getAuthorizedTenantIds();
        if (values == null || values.isEmpty()) {
            return List.of(entity.getRequestedTenantId());
        }
        return normalizeIdentifiers(values, "authorizedTenantIds");
    }

    private List<String> normalizeScopes(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return defaultAdminScopes();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String scope : scopes) {
            String canonical = canonicalizeScope(scope);
            if (StringUtils.hasText(canonical)) {
                normalized.add(canonical);
            }
        }
        if (normalized.isEmpty()) {
            return defaultAdminScopes();
        }
        return List.copyOf(normalized);
    }

    private String normalizeNamespace(ApproveUpstreamBootstrapRequestForm form,
                                      UpstreamBootstrapRequestEntity entity) {
        String namespace = form == null ? null : form.getAuthorizedClientAppNamespace();
        if (!StringUtils.hasText(namespace)) {
            namespace = entity.getUpstreamSystemId();
        }
        return requireIdentifier(namespace, "authorizedClientAppNamespace");
    }

    private long resolveClaimTtlMinutes(ApproveUpstreamBootstrapRequestForm form) {
        Long ttl = form == null ? null : form.getClaimTtlMinutes();
        if (ttl == null) {
            return DEFAULT_CLAIM_TTL_MINUTES;
        }
        if (ttl <= 0 || ttl > MAX_CLAIM_TTL_MINUTES) {
            throw new IllegalArgumentException("claimTtlMinutes must be between 1 and " + MAX_CLAIM_TTL_MINUTES);
        }
        return ttl;
    }

    private LocalDateTime resolveAdminCredentialExpiresAt(ApproveUpstreamBootstrapRequestForm form,
                                                          LocalDateTime now) {
        LocalDateTime expiresAt = form == null ? null : form.getCredentialExpiresAt();
        if (expiresAt != null) {
            return expiresAt;
        }
        return now.plusMinutes(DEFAULT_ADMIN_CREDENTIAL_TTL_MINUTES);
    }

    private LocalDateTime resolveRotatedCredentialExpiresAt(UpstreamClientAppAdminCredentialEntity oldCredential,
                                                            RotateUpstreamAdminCredentialForm form,
                                                            LocalDateTime now) {
        LocalDateTime requested = form == null ? null : form.getCredentialExpiresAt();
        if (requested != null) {
            return requested;
        }
        if (oldCredential.getExpiresAt() != null && oldCredential.getExpiresAt().isAfter(now)) {
            return oldCredential.getExpiresAt();
        }
        return now.plusMinutes(DEFAULT_ADMIN_CREDENTIAL_TTL_MINUTES);
    }

    private void validateCredentialExpiry(LocalDateTime expiresAt, LocalDateTime now) {
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new IllegalArgumentException("credentialExpiresAt must be in the future");
        }
    }

    private void validateRotatableCredential(UpstreamClientAppAdminCredentialEntity credential) {
        if (!CREDENTIAL_STATUS_ACTIVE.equals(credential.getStatus())) {
            throw new SecurityException("upstream admin credential is not active");
        }
        if (credential.getRevokedAt() != null) {
            throw new SecurityException("upstream admin credential revoked");
        }
        if (credential.getExpiresAt() != null && !credential.getExpiresAt().isAfter(LocalDateTime.now())) {
            throw new SecurityException("upstream admin credential expired");
        }
    }

    private UpstreamClientAppAdminCredentialEntity requireCredentialByIdForUpdate(String credentialId) {
        String id = requireText(credentialId, "credentialId is required");
        return adminCredentialRepository.findByCredentialIdForUpdate(id)
                .orElseThrow(() -> new IllegalArgumentException("upstream admin credential not found"));
    }

    private UpstreamBootstrapRequestEntity requireCredentialRequest(UpstreamClientAppAdminCredentialEntity credential,
                                                                    UpstreamBootstrapApprovalActor actor) {
        UpstreamBootstrapRequestEntity request = requestRepository.findByRequestId(credential.getSourceRequestId())
                .orElseThrow(() -> new IllegalStateException("source upstream bootstrap request not found"));
        requireActorCanManage(actor, request);
        return request;
    }

    private void throwClaimStateException(UpstreamBootstrapRequestEntity entity) {
        if (STATUS_CONSUMED.equals(entity.getStatus())) {
            throw new IllegalArgumentException("request already consumed");
        }
        if (STATUS_DENIED.equals(entity.getStatus())) {
            throw new IllegalArgumentException("request denied");
        }
        if (STATUS_EXPIRED.equals(entity.getStatus())) {
            throw new IllegalArgumentException("request expired");
        }
        throw new IllegalArgumentException("request is not approved");
    }

    public static String canonicalizeScope(String scope) {
        if (!StringUtils.hasText(scope)) {
            return null;
        }
        String normalized = scope.trim().replace('-', '_').toUpperCase();
        return switch (normalized) {
            case SCOPE_CLIENT_APP_ADMIN -> SCOPE_CLIENT_APP_MANAGE;
            case SCOPE_CONTROL_KEY_ISSUE -> SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE;
            case "DIRECTORY_MANAGE", "WORKDIR_MANAGE", "WORKING_DIR_MANAGE" -> SCOPE_WORKING_DIRECTORY_MANAGE;
            case "CONFIG_MODEL_MANAGE", "CONFIGMODEL_MANAGE" -> SCOPE_MODEL_CONFIG_MANAGE;
            case "AGENT_MANAGE", "AGENT_SYNC" -> SCOPE_AGENT_BUNDLE_SYNC;
            default -> normalized;
        };
    }

    private List<String> defaultAdminScopes() {
        return List.of(
                SCOPE_CLIENT_APP_MANAGE,
                SCOPE_CLIENT_APP_CONTROL_KEY_ISSUE,
                SCOPE_WORKER_MANAGE,
                SCOPE_WORKING_DIRECTORY_MANAGE,
                SCOPE_WORKER_POOL_MANAGE,
                SCOPE_MODEL_CONFIG_MANAGE,
                SCOPE_AGENT_BUNDLE_SYNC);
    }

    private UpstreamBootstrapRequestDTO toDTO(UpstreamBootstrapRequestEntity entity, boolean adminView) {
        UpstreamBootstrapRequestDTO dto = new UpstreamBootstrapRequestDTO();
        dto.setRequestId(adminView ? entity.getRequestId() : null);
        dto.setRequestCodeSuffix(entity.getRequestCodeSuffix());
        dto.setUpstreamSystemId(entity.getUpstreamSystemId());
        dto.setRequestedTenantId(adminView ? entity.getRequestedTenantId() : null);
        dto.setMultiTenant(entity.getMultiTenant());
        dto.setReason(adminView ? entity.getReason() : null);
        dto.setApplicantLabel(adminView ? entity.getApplicantLabel() : null);
        dto.setStatus(entity.getStatus());
        dto.setDeniedReason(STATUS_DENIED.equals(entity.getStatus()) ? entity.getDeniedReason() : null);
        dto.setAuthorizedTenantIds(adminView ? fromJson(entity.getAuthorizedTenantIdsJson()) : null);
        dto.setAuthorizedClientAppNamespace(adminView ? entity.getAuthorizedClientAppNamespace() : null);
        dto.setScopes(adminView ? fromJson(entity.getScopesJson()) : null);
        dto.setRequestExpiresAt(entity.getRequestExpiresAt());
        dto.setApprovedAt(entity.getApprovedAt());
        dto.setDeniedAt(entity.getDeniedAt());
        dto.setClaimExpiresAt(entity.getClaimExpiresAt());
        dto.setAdminCredentialExpiresAt(adminView ? entity.getAdminCredentialExpiresAt() : null);
        dto.setConsumedAt(entity.getConsumedAt());
        dto.setCreatedAt(entity.getCreatedAt());
        dto.setUpdatedAt(entity.getUpdatedAt());
        return dto;
    }

    private UpstreamAdminCredentialClaimDTO toClaimDTO(UpstreamClientAppAdminCredentialEntity credential,
                                                       String adminApiKey) {
        UpstreamAdminCredentialClaimDTO dto = new UpstreamAdminCredentialClaimDTO();
        dto.setCredentialId(credential.getCredentialId());
        dto.setNaviAdminApiKey(adminApiKey);
        dto.setUpstreamSystemId(credential.getUpstreamSystemId());
        dto.setAuthorizedTenantIds(fromJson(credential.getAuthorizedTenantIdsJson()));
        dto.setAuthorizedClientAppNamespace(credential.getAuthorizedClientAppNamespace());
        dto.setScopes(fromJson(credential.getScopesJson()));
        dto.setExpiresAt(credential.getExpiresAt());
        return dto;
    }

    private UpstreamAdminCredentialDTO toAdminCredentialDTO(UpstreamClientAppAdminCredentialEntity credential) {
        UpstreamAdminCredentialDTO dto = new UpstreamAdminCredentialDTO();
        dto.setCredentialId(credential.getCredentialId());
        dto.setUpstreamSystemId(credential.getUpstreamSystemId());
        dto.setAuthorizedTenantIds(fromJson(credential.getAuthorizedTenantIdsJson()));
        dto.setAuthorizedClientAppNamespace(credential.getAuthorizedClientAppNamespace());
        dto.setScopes(fromJson(credential.getScopesJson()));
        dto.setStatus(credential.getStatus());
        dto.setExpiresAt(credential.getExpiresAt());
        dto.setRevokedAt(credential.getRevokedAt());
        dto.setLastUsedAt(credential.getLastUsedAt());
        dto.setSourceRequestId(credential.getSourceRequestId());
        dto.setCreatedAt(credential.getCreatedAt());
        dto.setUpdatedAt(credential.getUpdatedAt());
        return dto;
    }

    private void recordAudit(UpstreamBootstrapRequestEntity request, String eventType,
                             String actorType, String actorId, String message) {
        if (!StringUtils.hasText(request.getRequestId())) {
            return;
        }
        UpstreamBootstrapAuditEntity audit = new UpstreamBootstrapAuditEntity();
        audit.setAuditId("uba_" + UUID.randomUUID());
        audit.setRequestId(request.getRequestId());
        audit.setRequestCodeSuffix(request.getRequestCodeSuffix());
        audit.setEventType(eventType);
        audit.setRequestStatus(request.getStatus());
        audit.setActorType(actorType);
        audit.setActorId(trimToNull(actorId, 128));
        audit.setMessage(trimToNull(message, 2000));
        auditRepository.save(audit);
    }

    private String actorType(UpstreamBootstrapApprovalActor actor) {
        return actor.isOperator() ? "OPERATOR" : "USER";
    }

    private String actorId(UpstreamBootstrapApprovalActor actor) {
        return actor.isOperator() ? actor.getOperatorCredentialId() : actor.getUserId();
    }

    private String normalizeStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return null;
        }
        String normalized = status.trim().toUpperCase();
        if (!List.of(STATUS_PENDING, STATUS_APPROVED, STATUS_DENIED, STATUS_EXPIRED, STATUS_CONSUMED)
                .contains(normalized)) {
            throw new IllegalArgumentException("unsupported request status: " + status);
        }
        return normalized;
    }

    private List<String> normalizeIdentifiers(List<String> values, String fieldName) {
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            normalized.add(requireIdentifier(value, fieldName));
        }
        return List.copyOf(normalized);
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
        String trimmed = value.trim();
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength);
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values == null ? List.of() : values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize upstream bootstrap data", e);
        }
    }

    private List<String> fromJson(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored upstream bootstrap data is invalid", e);
        }
    }

    private boolean constantTimeEquals(String left, String right) {
        if (!StringUtils.hasText(left) || !StringUtils.hasText(right)) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String prefix(String value) {
        if (value == null || value.length() <= 12) {
            return value;
        }
        return value.substring(0, 12);
    }

    private String suffix(String value) {
        if (value == null || value.length() <= 8) {
            return value;
        }
        return value.substring(value.length() - 8);
    }
}
