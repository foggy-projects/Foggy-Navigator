package com.foggy.navigator.common.authorization;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The complete P1B typed-management action-set registry. It deliberately has
 * exactly four fixed lanes; no legacy, runtime, task or Worker lane can be
 * dynamically mapped into this registry.
 */
@Component
public class ManagementActionSetRegistry {

    public static final String INSTANCE_ROOT_CONTROL_V1 =
            "navi.authorization.v1.instance-root-control";
    public static final String INSTANCE_ROOT_SECURITY_V1 =
            "navi.authorization.v1.instance-root-security";
    public static final String SAAS_PROVISIONING_V1 =
            "navi.authorization.v1.saas-provisioning";
    public static final String SAAS_SECURITY_ADMIN_V1 =
            "navi.authorization.v1.saas-security-admin";

    private static final Set<String> INTROSPECTION_ACTIONS = Set.of(
            "auth.whoami",
            "auth.permissions.inspect",
            "auth.decision.explain"
    );

    private final Map<AuthorizationCredentialLane, ManagementActionSetDefinition> definitionsByLane;
    private final Map<String, ManagementActionSetDefinition> definitionsByReference;
    private final Map<String, String> managementEndpointActions;

    public ManagementActionSetRegistry() {
        Map<AuthorizationCredentialLane, ManagementActionSetDefinition> definitions = new LinkedHashMap<>();
        definitions.put(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL,
                definition(AuthorizationCredentialLane.INSTANCE_ROOT_CONTROL, AuthorizationPrincipalType.INSTANCE_ROOT,
                        INSTANCE_ROOT_CONTROL_V1, union(INTROSPECTION_ACTIONS, Set.of(
                                "auth.exchange",
                                "instance.configure",
                                "upstream.manage",
                                "tenant.manage",
                                "client-app.manage",
                                "worker.manage",
                                "directory.manage",
                                "agent.manage",
                                "model.manage",
                                "binding.manage"
                        ))));
        definitions.put(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY,
                definition(AuthorizationCredentialLane.INSTANCE_ROOT_SECURITY, AuthorizationPrincipalType.INSTANCE_ROOT,
                        INSTANCE_ROOT_SECURITY_V1, union(INTROSPECTION_ACTIONS, Set.of(
                                "auth.security-authorize",
                                "credential.issue",
                                "credential.rotate",
                                "credential.revoke",
                                "credential.recover",
                                "grant.delegate",
                                "resource.transfer-owner",
                                "resource.delete",
                                "platform-grant.manage",
                                "tenant-authority.manage",
                                "trust-root.manage",
                                "production.promote"
                        ))));
        definitions.put(AuthorizationCredentialLane.SAAS_PROVISIONING,
                definition(AuthorizationCredentialLane.SAAS_PROVISIONING, AuthorizationPrincipalType.SAAS_PLATFORM,
                        SAAS_PROVISIONING_V1, union(INTROSPECTION_ACTIONS, Set.of(
                                "auth.exchange",
                                "tenant.create",
                                "tenant.manage",
                                "client-app.manage",
                                "worker.allocate",
                                "worker.manage",
                                "directory.manage",
                                "agent.manage",
                                "model.manage",
                                "binding.manage",
                                "grant.manage"
                        ))));
        definitions.put(AuthorizationCredentialLane.SAAS_SECURITY_ADMIN,
                definition(AuthorizationCredentialLane.SAAS_SECURITY_ADMIN, AuthorizationPrincipalType.SAAS_PLATFORM,
                        SAAS_SECURITY_ADMIN_V1, union(INTROSPECTION_ACTIONS, Set.of(
                                "auth.security-authorize",
                                "credential.rotate",
                                "credential.revoke",
                                "tenant.offboard",
                                "platform-grant.offboard",
                                "worker.reassign",
                                "worker.delete",
                                "grant.revoke"
                        ))));
        this.definitionsByLane = Map.copyOf(definitions);
        Map<String, ManagementActionSetDefinition> byReference = new LinkedHashMap<>();
        definitions.values().forEach(definition -> byReference.put(definition.actionSetRef(), definition));
        this.definitionsByReference = Map.copyOf(byReference);
        this.managementEndpointActions = Map.of(
                "mvc:post:/api/v1/management/v1/auth/exchange", "auth.exchange",
                "mvc:post:/api/v1/management/v1/auth/security-actions/authorize", "auth.security-authorize",
                "mvc:get:/api/v1/management/v1/auth/whoami", "auth.whoami",
                "mvc:get:/api/v1/management/v1/auth/permissions", "auth.permissions.inspect",
                "mvc:post:/api/v1/management/v1/auth/explain", "auth.decision.explain"
        );
    }

    public Map<AuthorizationCredentialLane, ManagementActionSetDefinition> definitionsByLane() {
        return definitionsByLane;
    }

    public Optional<ManagementActionSetDefinition> findByLane(AuthorizationCredentialLane credentialLane) {
        return Optional.ofNullable(definitionsByLane.get(credentialLane));
    }

    public Optional<ManagementActionSetDefinition> findByReference(String actionSetReference) {
        return Optional.ofNullable(definitionsByReference.get(actionSetReference));
    }

    public boolean matches(AuthorizationPrincipalType principalType,
                           AuthorizationCredentialLane credentialLane,
                           String actionSetReference) {
        return findByLane(credentialLane)
                .filter(definition -> definition.principalType() == principalType)
                .filter(definition -> definition.actionSetRef().equals(actionSetReference))
                .isPresent();
    }

    public boolean allows(String actionSetReference, String actionId) {
        return findByReference(actionSetReference)
                .map(ManagementActionSetDefinition::actions)
                .map(actions -> actions.contains(actionId))
                .orElse(false);
    }

    public Set<String> effectiveActions(String actionSetReference) {
        return findByReference(actionSetReference)
                .map(ManagementActionSetDefinition::actions)
                .orElse(Set.of());
    }

    public Set<String> authorityCeilingActions(AuthorizationPrincipalType principalType) {
        Set<String> actions = new LinkedHashSet<>();
        definitionsByLane.values().stream()
                .filter(definition -> definition.principalType() == principalType)
                .forEach(definition -> actions.addAll(definition.actions()));
        return Set.copyOf(actions);
    }

    /** New P1B auth endpoints must match this source-controlled route/action pair exactly. */
    public boolean isRegisteredEndpointAction(String routeId, String actionId) {
        return actionId != null && actionId.equals(managementEndpointActions.get(routeId));
    }

    public Map<String, String> managementEndpointActions() {
        return managementEndpointActions;
    }

    private static ManagementActionSetDefinition definition(AuthorizationCredentialLane lane,
                                                              AuthorizationPrincipalType principalType,
                                                              String reference,
                                                              Set<String> actions) {
        return new ManagementActionSetDefinition(lane, principalType, reference, actions);
    }

    private static Set<String> union(Set<String> left, Set<String> right) {
        Set<String> merged = new LinkedHashSet<>(left);
        merged.addAll(right);
        return Set.copyOf(merged);
    }
}
