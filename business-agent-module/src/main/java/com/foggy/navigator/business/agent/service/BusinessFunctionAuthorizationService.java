package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessFunctionAuthorizationService {

    private final ClientAppService clientAppService;
    private final ClientAppUserGrantService userGrantService;
    private final SkillRegistryService skillRegistryService;
    private final BusinessFunctionRegistryService functionRegistryService;

    /**
     * Resolves and verifies the full authorization chain for a business function call.
     * This method enforces a fail-closed policy on user/client function permission,
     * sequentially checking:
     * 1. Client App is active
     * 2. Upstream User Grant is ENABLED
     * 3. Client App Skill Grant is ENABLED
     * 4. Business Function, Version, and App Function Grant are valid
     *
     * SkillFunctionAllowlist is intentionally not a runtime hard gate here. It is
     * used for skill materialization and recommendation scope, while actual
     * execution is controlled by user/client function grants.
     */
    @Transactional(readOnly = true)
    public BusinessFunctionRuntimeContextDTO resolveExecutableBusinessFunction(
            String tenantId,
            String clientAppId,
            String upstreamUserId,
            String skillId,
            String functionId,
            String version) {

        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(clientAppId, "clientAppId is required");
        Assert.hasText(upstreamUserId, "upstreamUserId is required");
        Assert.hasText(skillId, "skillId is required");
        Assert.hasText(functionId, "functionId is required");
        Assert.hasText(version, "version is required");

        // 1. ClientApp active
        clientAppService.requireActiveClientApp(tenantId, clientAppId);

        // 2. upstreamUserId 非空且 ClientAppUpstreamUserGrant ENABLED
        userGrantService.checkUpstreamUserAccess(tenantId, clientAppId, upstreamUserId);

        // 3. skillId 非空且 Skill ENABLED, ClientAppSkillGrant ENABLED
        skillRegistryService.checkClientAppSkillAccess(tenantId, clientAppId, skillId);

        // 4. BusinessFunction / BusinessFunctionVersion / ClientAppFunctionGrant 均有效
        return functionRegistryService.resolveClientAppFunction(tenantId, clientAppId, functionId, version);
    }
}
