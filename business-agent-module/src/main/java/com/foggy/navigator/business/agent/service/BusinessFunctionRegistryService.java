package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionSummaryDTO;
import com.foggy.navigator.business.agent.model.dto.BusinessFunctionVersionDTO;
import com.foggy.navigator.business.agent.model.dto.ClientAppFunctionGrantDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppEntity;
import com.foggy.navigator.business.agent.model.entity.ClientAppFunctionGrantEntity;
import com.foggy.navigator.business.agent.model.form.GrantBusinessFunctionForm;
import com.foggy.navigator.business.agent.model.form.ImportBusinessFunctionManifestForm;
import com.foggy.navigator.business.agent.repository.BusinessFunctionRepository;
import com.foggy.navigator.business.agent.repository.BusinessFunctionVersionRepository;
import com.foggy.navigator.business.agent.repository.ClientAppFunctionGrantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BusinessFunctionRegistryService {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private final BusinessFunctionRepository functionRepository;
    private final BusinessFunctionVersionRepository versionRepository;
    private final ClientAppFunctionGrantRepository grantRepository;
    private final ClientAppService clientAppService;
    private final BusinessObjectService businessObjectService;

    @Transactional
    public void importManifest(String tenantId, String actorUserId, ImportBusinessFunctionManifestForm form) {
        requireText(tenantId, "tenantId is required");
        requireText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getFunctionId(), "functionId is required");
        requireText(form.getVersion(), "version is required");
        requireText(form.getDomain(), "domain is required");
        requireText(form.getName(), "name is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        // Create or update BusinessFunctionEntity
        BusinessFunctionEntity function = functionRepository.findByTenantIdAndFunctionId(tenantId, form.getFunctionId())
                .orElseGet(BusinessFunctionEntity::new);

        if (StringUtils.hasText(form.getBusinessObjectId())) {
            businessObjectService.requireActiveBusinessObject(tenantId, form.getBusinessObjectId());
            function.setBusinessObjectId(form.getBusinessObjectId());
        }

        function.setTenantId(tenantId);
        function.setFunctionId(form.getFunctionId());
        function.setDomain(form.getDomain());
        function.setName(form.getName());
        function.setDescription(form.getDescription());
        function.setCurrentVersion(form.getVersion());
        function.setExposure(form.getExposure());
        function.setRiskLevel(form.getRiskLevel());
        function.setApprovalRequired(form.getApprovalRequired() != null ? form.getApprovalRequired() : false);
        function.setIdempotencyRequired(form.getIdempotencyRequired() != null ? form.getIdempotencyRequired() : false);
        function.setStatus(status);
        if (!StringUtils.hasText(function.getCreatedBy())) {
            function.setCreatedBy(actorUserId);
        }

        functionRepository.save(function);

        // Add new BusinessFunctionVersionEntity
        Optional<BusinessFunctionVersionEntity> existingVersion = versionRepository.findByTenantIdAndFunctionIdAndVersion(
                tenantId, form.getFunctionId(), form.getVersion());

        if (existingVersion.isPresent()) {
            throw new IllegalArgumentException("Version already exists: " + form.getVersion());
        }

        BusinessFunctionVersionEntity version = new BusinessFunctionVersionEntity();
        version.setTenantId(tenantId);
        version.setFunctionId(form.getFunctionId());
        version.setVersion(form.getVersion());
        version.setManifestJson(form.getManifestJson());
        version.setInputSchemaJson(form.getInputSchemaJson());
        version.setOutputSchemaJson(form.getOutputSchemaJson());
        version.setLlmVisibleSummary(form.getLlmVisibleSummary());
        version.setSchemaVisibleSummary(form.getSchemaVisibleSummary());
        version.setAdapterConfigJson(form.getAdapterConfigJson());
        version.setStatus(status);

        versionRepository.save(version);
    }

    @Transactional
    public ClientAppFunctionGrantDTO grantFunctionToClientApp(String tenantId, String clientAppId, String actorUserId, GrantBusinessFunctionForm form) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        requireText(form.getFunctionId(), "functionId is required");
        requireText(form.getVersion(), "version is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        // Validate Client App
        ClientAppEntity app = clientAppService.requireActiveClientApp(tenantId, clientAppId);

        // Validate Function
        BusinessFunctionEntity function = functionRepository.findByTenantIdAndFunctionId(tenantId, form.getFunctionId())
                .orElseThrow(() -> new IllegalArgumentException("function not found"));
        if (!STATUS_ENABLED.equals(function.getStatus())) {
            throw new IllegalStateException("function is not enabled");
        }

        // Validate Function Version
        BusinessFunctionVersionEntity version = versionRepository.findByTenantIdAndFunctionIdAndVersion(tenantId, form.getFunctionId(), form.getVersion())
                .orElseThrow(() -> new IllegalArgumentException("function version not found"));
        if (!STATUS_ENABLED.equals(version.getStatus())) {
            throw new IllegalStateException("function version is not enabled");
        }

        // Check duplicate grant
        Optional<ClientAppFunctionGrantEntity> existingGrant = grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion(
                tenantId, clientAppId, form.getFunctionId(), form.getVersion());
        if (existingGrant.isPresent()) {
            throw new IllegalArgumentException("grant already exists for this function and version");
        }

        ClientAppFunctionGrantEntity grant = new ClientAppFunctionGrantEntity();
        grant.setGrantId("cafg_" + UUID.randomUUID());
        grant.setTenantId(tenantId);
        grant.setClientAppId(clientAppId);
        grant.setFunctionId(form.getFunctionId());
        grant.setVersion(form.getVersion());
        grant.setStatus(status);
        grant.setCreatedBy(actorUserId);

        grant = grantRepository.save(grant);
        return ClientAppFunctionGrantDTO.fromEntity(grant);
    }

    @Transactional
    public ClientAppFunctionGrantDTO updateGrantStatus(String tenantId, String clientAppId, String grantId, String status) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(grantId, "grantId is required");
        requireText(status, "status is required");

        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        ClientAppFunctionGrantEntity grant = grantRepository.findByGrantIdAndTenantId(grantId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("grant not found"));

        if (!grant.getClientAppId().equals(clientAppId)) {
            throw new IllegalArgumentException("grant does not belong to the specified client app");
        }

        grant.setStatus(status);
        return ClientAppFunctionGrantDTO.fromEntity(grantRepository.save(grant));
    }

    @Transactional(readOnly = true)
    public BusinessFunctionRuntimeContextDTO resolveClientAppFunction(String tenantId, String clientAppId, String functionId, String version) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");
        requireText(functionId, "functionId is required");
        requireText(version, "version is required");

        // Validate App
        clientAppService.requireActiveClientApp(tenantId, clientAppId);

        // Validate Function
        BusinessFunctionEntity function = functionRepository.findByTenantIdAndFunctionId(tenantId, functionId)
                .orElseThrow(() -> new IllegalArgumentException("function not found"));
        if (!STATUS_ENABLED.equals(function.getStatus())) {
            throw new IllegalStateException("function is not enabled");
        }

        // Validate Version
        BusinessFunctionVersionEntity funcVersion = versionRepository.findByTenantIdAndFunctionIdAndVersion(tenantId, functionId, version)
                .orElseThrow(() -> new IllegalArgumentException("function version not found"));
        if (!STATUS_ENABLED.equals(funcVersion.getStatus())) {
            throw new IllegalStateException("function version is not enabled");
        }

        // Validate Grant
        ClientAppFunctionGrantEntity grant = grantRepository.findByTenantIdAndClientAppIdAndFunctionIdAndVersion(
                tenantId, clientAppId, functionId, version)
                .orElseThrow(() -> new IllegalArgumentException("grant not found"));
        if (!STATUS_ENABLED.equals(grant.getStatus())) {
            throw new IllegalStateException("grant is not enabled");
        }

        return BusinessFunctionRuntimeContextDTO.fromEntity(function, funcVersion);
    }

    @Transactional(readOnly = true)
    public List<BusinessFunctionSummaryDTO> listClientAppVisibleFunctionSummaries(String tenantId, String clientAppId) {
        requireText(tenantId, "tenantId is required");
        requireText(clientAppId, "clientAppId is required");

        // Validate App
        clientAppService.requireActiveClientApp(tenantId, clientAppId);

        List<ClientAppFunctionGrantEntity> grants = grantRepository.findByTenantIdAndClientAppId(tenantId, clientAppId);

        return grants.stream()
                .filter(grant -> STATUS_ENABLED.equals(grant.getStatus()))
                .map(grant -> {
                    Optional<BusinessFunctionEntity> funcOpt = functionRepository.findByTenantIdAndFunctionId(tenantId, grant.getFunctionId());
                    Optional<BusinessFunctionVersionEntity> verOpt = versionRepository.findByTenantIdAndFunctionIdAndVersion(tenantId, grant.getFunctionId(), grant.getVersion());

                    if (funcOpt.isEmpty() || verOpt.isEmpty()) {
                        return null;
                    }
                    BusinessFunctionEntity func = funcOpt.get();
                    BusinessFunctionVersionEntity ver = verOpt.get();

                    if (!STATUS_ENABLED.equals(func.getStatus()) || !STATUS_ENABLED.equals(ver.getStatus())) {
                        return null;
                    }

                    BusinessFunctionSummaryDTO summary = new BusinessFunctionSummaryDTO();
                    summary.setFunctionId(func.getFunctionId());
                    summary.setVersion(ver.getVersion());
                    summary.setDomain(func.getDomain());
                    summary.setName(func.getName());
                    summary.setDescription(func.getDescription());
                    summary.setRiskLevel(func.getRiskLevel());
                    summary.setApprovalRequired(func.getApprovalRequired());
                    summary.setIdempotencyRequired(func.getIdempotencyRequired());
                    summary.setLlmVisibleSummary(ver.getLlmVisibleSummary());
                    summary.setSchemaVisibleSummary(ver.getSchemaVisibleSummary());
                    return summary;
                })
                .filter(summary -> summary != null)
                .collect(Collectors.toList());
    }

    private void requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
    }
}
