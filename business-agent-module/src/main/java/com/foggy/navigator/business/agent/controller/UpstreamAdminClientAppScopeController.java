package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.business.agent.model.dto.*;
import com.foggy.navigator.business.agent.model.form.*;
import com.foggy.navigator.business.agent.service.*;
import com.foggyframework.core.ex.RX;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * S1 system-admin endpoints for an explicitly targeted ClientApp. Existing ClientApp control
 * endpoints remain control-credential-only; this separate surface prevents mixed credentials.
 */
@RestController
@RequestMapping("/api/v1/upstream-admin/client-apps/{clientAppId}/scope")
@RequiredArgsConstructor
public class UpstreamAdminClientAppScopeController {

    private final UpstreamAdminClientAppScopeService scopeService;
    private final BusinessAgentBundleService agentBundleService;
    private final ClientAppModelConfigGrantService modelGrantService;
    private final ClientAppOwnedModelConfigService ownedModelConfigService;
    private final ClientAppUserGrantService userGrantService;
    private final AgentModelBindingService modelBindingService;
    private final AgentWorkspaceBindingService workspaceBindingService;
    private final AgentWorkerBindingService workerBindingService;

    @GetMapping
    public RX<UpstreamAdminClientAppScopeDTO> inspect(HttpServletRequest request,
                                                      @PathVariable String clientAppId) {
        return RX.ok(scopeService.inspect(request, clientAppId));
    }

    @GetMapping("/agents")
    public RX<List<BusinessAgentBundleDTO>> listAgents(HttpServletRequest request,
                                                        @PathVariable String clientAppId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_BUNDLE_SYNC);
        return RX.ok(agentBundleService.listClientAppOwnedAgents(scope.principal().getTenantId(), clientAppId));
    }

    @GetMapping("/agents/{agentId}")
    public RX<BusinessAgentBundleDTO> getAgent(HttpServletRequest request,
                                                @PathVariable String clientAppId,
                                                @PathVariable String agentId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_BUNDLE_SYNC);
        return RX.ok(agentBundleService.getClientAppOwnedAgent(scope.principal().getTenantId(), clientAppId, agentId));
    }

    @PostMapping("/agents/sync")
    public RX<BusinessAgentBundleDTO> syncAgent(HttpServletRequest request,
                                                @PathVariable String clientAppId,
                                                @RequestBody SyncBusinessAgentBundleForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_BUNDLE_SYNC);
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        if (form.getClientAppId() != null && !clientAppId.equals(form.getClientAppId().trim())) {
            throw new SecurityException("form clientAppId does not match target client app");
        }
        form.setClientAppId(clientAppId);
        return RX.ok(agentBundleService.syncAgentBundle(
                scope.principal().getTenantId(), scope.principal().getActorUserId(), form));
    }

    @GetMapping("/model-config-grants")
    public RX<List<ClientAppModelConfigGrantDTO>> listModelGrants(HttpServletRequest request,
                                                                   @PathVariable String clientAppId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(modelGrantService.listGrants(scope.principal().getTenantId(), clientAppId));
    }

    @PostMapping("/model-config-grants")
    public RX<ClientAppModelConfigGrantDTO> grantModel(HttpServletRequest request,
                                                        @PathVariable String clientAppId,
                                                        @RequestBody GrantModelConfigForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(modelGrantService.grantModelConfig(scope.principal().getTenantId(),
                scope.principal().getActorUserId(), clientAppId, form));
    }

    @PutMapping("/model-config-grants/{grantId}/status")
    public RX<ClientAppModelConfigGrantDTO> updateModelGrantStatus(HttpServletRequest request,
                                                                    @PathVariable String clientAppId,
                                                                    @PathVariable Long grantId,
                                                                    @RequestBody UpdateStatusForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(modelGrantService.updateStatus(scope.principal().getTenantId(), clientAppId,
                grantId, form == null ? null : form.getStatus()));
    }

    @PutMapping("/model-config-grants/{grantId}/default")
    public RX<ClientAppModelConfigGrantDTO> setDefaultModelGrant(HttpServletRequest request,
                                                                  @PathVariable String clientAppId,
                                                                  @PathVariable Long grantId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(modelGrantService.setDefault(scope.principal().getTenantId(), clientAppId, grantId));
    }

    @PostMapping("/model-configs")
    public RX<ClientAppModelConfigGrantDTO> createModel(HttpServletRequest request,
                                                         @PathVariable String clientAppId,
                                                         @RequestBody ClientAppModelConfigForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(ownedModelConfigService.create(scope.principal().getTenantId(),
                scope.principal().getActorUserId(), clientAppId, form));
    }

    @GetMapping("/model-configs/{modelConfigId}")
    public RX<com.foggy.navigator.common.dto.LlmModelConfigDTO> getModel(HttpServletRequest request,
                                                                           @PathVariable String clientAppId,
                                                                           @PathVariable String modelConfigId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(ownedModelConfigService.get(scope.principal().getTenantId(), clientAppId, modelConfigId));
    }

    @PutMapping("/model-configs/{modelConfigId}")
    public RX<ClientAppModelConfigGrantDTO> updateModel(HttpServletRequest request,
                                                         @PathVariable String clientAppId,
                                                         @PathVariable String modelConfigId,
                                                         @RequestBody ClientAppModelConfigForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(ownedModelConfigService.update(scope.principal().getTenantId(), clientAppId, modelConfigId, form));
    }

    @PutMapping("/model-configs/{modelConfigId}/key")
    public RX<ClientAppModelConfigGrantDTO> rotateModelKey(HttpServletRequest request,
                                                            @PathVariable String clientAppId,
                                                            @PathVariable String modelConfigId,
                                                            @RequestBody RotateModelConfigKeyForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_MODEL_CONFIG_MANAGE);
        return RX.ok(ownedModelConfigService.rotateKey(scope.principal().getTenantId(), clientAppId, modelConfigId, form));
    }

    @GetMapping("/upstream-users")
    public RX<List<ClientAppUpstreamUserGrantDTO>> listUpstreamUsers(HttpServletRequest request,
                                                                      @PathVariable String clientAppId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        return RX.ok(userGrantService.listUpstreamUserGrants(scope.principal().getTenantId(), clientAppId));
    }

    @PostMapping("/upstream-users")
    public RX<ClientAppUpstreamUserGrantDTO> grantUpstreamUser(HttpServletRequest request,
                                                                @PathVariable String clientAppId,
                                                                @RequestBody GrantUpstreamUserForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        return RX.ok(userGrantService.grantUpstreamUserAccess(scope.principal().getTenantId(), clientAppId,
                scope.principal().getActorUserId(), form));
    }

    @PutMapping("/upstream-users/{upstreamUserId}/status")
    public RX<ClientAppUpstreamUserGrantDTO> updateUpstreamUserStatus(HttpServletRequest request,
                                                                       @PathVariable String clientAppId,
                                                                       @PathVariable String upstreamUserId,
                                                                       @RequestParam String status) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_CLIENT_APP_MANAGE);
        return RX.ok(userGrantService.updateUpstreamUserGrantStatus(scope.principal().getTenantId(), clientAppId,
                upstreamUserId, status));
    }

    @GetMapping("/agents/{agentId}/model-bindings")
    public RX<List<AgentModelBindingDTO>> listModelBindings(HttpServletRequest request, @PathVariable String clientAppId,
                                                             @PathVariable String agentId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_MODEL_BINDING_MANAGE);
        return RX.ok(modelBindingService.list(scope.principal().getTenantId(), clientAppId, agentId));
    }

    @PostMapping("/agents/{agentId}/model-bindings")
    public RX<AgentModelBindingDTO> bindModel(HttpServletRequest request, @PathVariable String clientAppId,
                                              @PathVariable String agentId, @RequestBody BindAgentModelForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_MODEL_BINDING_MANAGE);
        return RX.ok(modelBindingService.bind(scope.principal().getTenantId(), clientAppId, agentId, form));
    }

    @PutMapping("/agents/{agentId}/model-bindings/default")
    public RX<AgentModelBindingDTO> defaultModel(HttpServletRequest request, @PathVariable String clientAppId,
                                                 @PathVariable String agentId, @RequestBody BindAgentModelForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_MODEL_BINDING_MANAGE);
        return RX.ok(modelBindingService.setDefault(scope.principal().getTenantId(), clientAppId, agentId, form));
    }

    @DeleteMapping("/agents/{agentId}/model-bindings/{modelConfigId}")
    public RX<Void> unbindModel(HttpServletRequest request, @PathVariable String clientAppId,
                                @PathVariable String agentId, @PathVariable String modelConfigId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_MODEL_BINDING_MANAGE);
        modelBindingService.unbind(scope.principal().getTenantId(), clientAppId, agentId, modelConfigId);
        return RX.ok(null);
    }

    @GetMapping("/agents/{agentId}/workspace-bindings")
    public RX<List<AgentWorkspaceBindingDTO>> listWorkspaceBindings(HttpServletRequest request, @PathVariable String clientAppId,
                                                                     @PathVariable String agentId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE);
        return RX.ok(workspaceBindingService.list(scope.principal().getTenantId(), clientAppId, agentId));
    }

    @PostMapping("/agents/{agentId}/workspace-bindings")
    public RX<AgentWorkspaceBindingDTO> bindWorkspace(HttpServletRequest request, @PathVariable String clientAppId,
                                                       @PathVariable String agentId, @RequestBody BindAgentWorkspaceForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE);
        return RX.ok(workspaceBindingService.bind(scope.principal().getTenantId(), clientAppId, agentId, form));
    }

    @PutMapping("/agents/{agentId}/workspace-bindings/default")
    public RX<AgentWorkspaceBindingDTO> defaultWorkspace(HttpServletRequest request, @PathVariable String clientAppId,
                                                          @PathVariable String agentId, @RequestBody BindAgentWorkspaceForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE);
        return RX.ok(workspaceBindingService.setDefault(scope.principal().getTenantId(), clientAppId, agentId, form));
    }

    @DeleteMapping("/agents/{agentId}/workspace-bindings/{directoryId}")
    public RX<Void> unbindWorkspace(HttpServletRequest request, @PathVariable String clientAppId,
                                    @PathVariable String agentId, @PathVariable String directoryId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKSPACE_BINDING_MANAGE);
        workspaceBindingService.unbind(scope.principal().getTenantId(), clientAppId, agentId, directoryId);
        return RX.ok(null);
    }

    @GetMapping("/agents/{agentId}/worker-bindings")
    public RX<List<AgentWorkerBindingDTO>> listWorkerBindings(HttpServletRequest request, @PathVariable String clientAppId,
                                                               @PathVariable String agentId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKER_BINDING_MANAGE);
        return RX.ok(workerBindingService.list(scope.principal().getTenantId(), clientAppId, agentId));
    }

    @PostMapping("/agents/{agentId}/worker-bindings")
    public RX<AgentWorkerBindingDTO> bindWorker(HttpServletRequest request, @PathVariable String clientAppId,
                                                @PathVariable String agentId, @RequestBody BindAgentWorkerForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKER_BINDING_MANAGE);
        return RX.ok(workerBindingService.bind(scope.principal().getTenantId(), clientAppId, agentId, form));
    }

    @PutMapping("/agents/{agentId}/worker-bindings/default")
    public RX<AgentWorkerBindingDTO> defaultWorker(HttpServletRequest request, @PathVariable String clientAppId,
                                                   @PathVariable String agentId, @RequestBody BindAgentWorkerForm form) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKER_BINDING_MANAGE);
        return RX.ok(workerBindingService.setDefault(scope.principal().getTenantId(), clientAppId, agentId, form));
    }

    @DeleteMapping("/agents/{agentId}/worker-bindings/{workerPoolId}")
    public RX<Void> unbindWorker(HttpServletRequest request, @PathVariable String clientAppId,
                                 @PathVariable String agentId, @PathVariable String workerPoolId) {
        var scope = scope(request, clientAppId, UpstreamBootstrapRequestService.SCOPE_AGENT_WORKER_BINDING_MANAGE);
        workerBindingService.unbind(scope.principal().getTenantId(), clientAppId, agentId, workerPoolId);
        return RX.ok(null);
    }

    private UpstreamAdminClientAppScopeService.ResolvedScope scope(HttpServletRequest request,
                                                                    String clientAppId,
                                                                    String requiredScope) {
        return scopeService.requireScope(request, clientAppId, requiredScope);
    }
}
