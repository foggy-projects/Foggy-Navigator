package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.business.agent.model.form.CreateBusinessObjectForm;
import com.foggy.navigator.business.agent.model.form.UpdateBusinessObjectForm;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class BizWorkerControlPlaneAuthorizationTest {

    @Test
    void clientAppController_requires_tenant_admin() {
        assertClassRole(ClientAppController.class, "TENANT_ADMIN");
    }

    @Test
    void adminClientAppController_requires_tenant_admin() {
        assertClassRole(AdminClientAppController.class, "TENANT_ADMIN");
    }

    @Test
    void adminUpstreamTenantClientAppProvisioningController_uses_upstream_admin_key_guard() {
        assertNull(AdminUpstreamTenantClientAppProvisioningController.class.getAnnotation(RequireAuth.class));
    }

    @Test
    void upstreamClientAppAdminController_uses_upstream_admin_key_guard() {
        assertNull(UpstreamClientAppAdminController.class.getAnnotation(RequireAuth.class));
    }

    @Test
    void adminUpstreamAdminCredentialController_uses_operator_or_admin_guard() {
        assertNull(AdminUpstreamAdminCredentialController.class.getAnnotation(RequireAuth.class));
    }

    @Test
    void modelConfigGrantController_uses_client_app_control_plane_guard() {
        assertNull(ClientAppModelConfigGrantController.class.getAnnotation(RequireAuth.class));
    }

    @Test
    void clientAppOwnedModelConfigController_uses_client_app_control_plane_guard() {
        assertNull(ClientAppOwnedModelConfigController.class.getAnnotation(RequireAuth.class));
    }

    @Test
    void e2eModelConfigEnsure_uses_client_app_control_plane_guard() throws NoSuchMethodException {
        Method method = E2eModelConfigController.class.getMethod(
                "ensure",
                jakarta.servlet.http.HttpServletRequest.class,
                String.class,
                com.foggy.navigator.business.agent.model.form.EnsureE2eModelConfigForm.class);
        assertNull(method.getAnnotation(RequireAuth.class));
    }

    @Test
    void workerIdentity_registration_requires_super_admin() throws NoSuchMethodException {
        Method method = BizWorkerPoolController.class
                .getMethod("registerWorkerIdentity", com.foggy.navigator.business.agent.model.form.RegisterWorkerIdentityForm.class);

        assertMethodRole(method, "SUPER_ADMIN");
    }

    @Test
    void workerPool_control_plane_requires_login_only() throws NoSuchMethodException {
        assertMethodHasNoRoleRequirement(BizWorkerPoolController.class.getMethod("listPools"));
        assertMethodHasNoRoleRequirement(BizWorkerPoolController.class.getMethod(
                "createPool", com.foggy.navigator.business.agent.model.form.CreateWorkerPoolForm.class));
        assertMethodHasNoRoleRequirement(BizWorkerPoolController.class.getMethod(
                "addMember", String.class, com.foggy.navigator.business.agent.model.form.AddWorkerPoolMemberForm.class));
        assertMethodHasNoRoleRequirement(BizWorkerPoolController.class.getMethod(
                "updatePoolStatus", String.class, com.foggy.navigator.business.agent.model.form.UpdateStatusForm.class));
    }

    private void assertClassRole(Class<?> controllerClass, String role) {
        RequireAuth annotation = controllerClass.getAnnotation(RequireAuth.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.roles()).contains(role));
    }

    private void assertMethodRole(Method method, String role) {
        RequireAuth annotation = method.getAnnotation(RequireAuth.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.roles()).contains(role));
    }

    private void assertMethodHasNoRoleRequirement(Method method) {
        RequireAuth annotation = method.getAnnotation(RequireAuth.class);
        assertNotNull(annotation);
        assertEquals(0, annotation.roles().length);
    }

    @Test
    void businessAgentTaskController_methods_requires_tenant_admin() throws NoSuchMethodException {
        assertMethodRole(BusinessAgentTaskController.class.getMethod("createTask", String.class, String.class, com.foggy.navigator.business.agent.model.form.CreateBusinessAgentTaskForm.class), "TENANT_ADMIN");
        assertMethodRole(BusinessAgentTaskController.class.getMethod("getTask", String.class, String.class), "TENANT_ADMIN");
        assertMethodRole(BusinessAgentTaskController.class.getMethod("listTasksBySession", String.class, String.class), "TENANT_ADMIN");
    }

    @Test
    void businessFunctionRegistryController_uses_client_app_control_plane_guard() throws NoSuchMethodException {
        assertNull(com.foggy.navigator.business.agent.controller.BusinessFunctionRegistryController.class
                .getMethod("importManifest", jakarta.servlet.http.HttpServletRequest.class,
                        com.foggy.navigator.business.agent.model.form.ImportBusinessFunctionManifestForm.class)
                .getAnnotation(RequireAuth.class));
        assertNull(com.foggy.navigator.business.agent.controller.BusinessFunctionRegistryController.class
                .getMethod("grantFunctionToClientApp", jakarta.servlet.http.HttpServletRequest.class, String.class,
                        com.foggy.navigator.business.agent.model.form.GrantBusinessFunctionForm.class)
                .getAnnotation(RequireAuth.class));
        assertNull(com.foggy.navigator.business.agent.controller.BusinessFunctionRegistryController.class
                .getMethod("updateGrantStatus", jakarta.servlet.http.HttpServletRequest.class, String.class, String.class, String.class)
                .getAnnotation(RequireAuth.class));
        assertNull(com.foggy.navigator.business.agent.controller.BusinessFunctionRegistryController.class
                .getMethod("listClientAppVisibleFunctionSummaries", jakarta.servlet.http.HttpServletRequest.class, String.class)
                .getAnnotation(RequireAuth.class));
    }

    @Test
    void skillRegistryController_methods_requires_tenant_admin() throws NoSuchMethodException {
        assertMethodRole(com.foggy.navigator.business.agent.controller.SkillRegistryController.class.getMethod("createSkill", String.class, String.class, com.foggy.navigator.business.agent.model.form.CreateSkillForm.class), "TENANT_ADMIN");
        assertMethodRole(com.foggy.navigator.business.agent.controller.SkillRegistryController.class.getMethod("addFunctionToSkillAllowlist", String.class, String.class, String.class, com.foggy.navigator.business.agent.model.form.AddFunctionToSkillForm.class), "TENANT_ADMIN");
        assertMethodRole(com.foggy.navigator.business.agent.controller.SkillRegistryController.class.getMethod("grantSkillToClientApp", String.class, String.class, String.class, com.foggy.navigator.business.agent.model.form.GrantSkillToClientAppForm.class), "TENANT_ADMIN");
        assertMethodRole(com.foggy.navigator.business.agent.controller.SkillRegistryController.class.getMethod("materializePublicSkill", String.class, String.class), "TENANT_ADMIN");
    }

    @Test
    void clientAppUserGrantController_methods_requires_tenant_admin() throws NoSuchMethodException {
        assertNull(com.foggy.navigator.business.agent.controller.ClientAppUserGrantController.class
                .getMethod("grantUpstreamUserAccess", jakarta.servlet.http.HttpServletRequest.class, String.class,
                        com.foggy.navigator.business.agent.model.form.GrantUpstreamUserForm.class)
                .getAnnotation(RequireAuth.class));
        assertNull(com.foggy.navigator.business.agent.controller.ClientAppUserGrantController.class
                .getMethod("updateUpstreamUserGrantStatus", jakarta.servlet.http.HttpServletRequest.class, String.class, String.class, String.class)
                .getAnnotation(RequireAuth.class));
    }

    @Test
    void testBusinessObjectController() throws Exception {
        assertMethodRole(
                BusinessObjectController.class.getMethod("createBusinessObject", String.class, String.class, CreateBusinessObjectForm.class),
                "TENANT_ADMIN"
        );
        assertMethodRole(
                BusinessObjectController.class.getMethod("getBusinessObject", String.class, String.class),
                "TENANT_ADMIN"
        );
        assertMethodRole(
                BusinessObjectController.class.getMethod("updateBusinessObject", String.class, String.class, String.class, UpdateBusinessObjectForm.class),
                "TENANT_ADMIN"
        );
    }

    @Test
    void testBusinessFunctionApprovalController() throws Exception {
        assertMethodRole(
                BusinessFunctionApprovalController.class.getMethod("resumeSuspension", String.class, String.class, String.class, com.foggy.navigator.business.agent.model.form.WorkerGatewayResumeForm.class),
                "TENANT_ADMIN"
        );
    }

    @Test
    void testWorkerGatewayController_hasNoTenantAdminRole() throws Exception {
        // Internal gateway should not require TENANT_ADMIN role
        assertNull(WorkerGatewayController.class.getAnnotation(RequireAuth.class));
        assertNull(WorkerGatewayController.class.getMethod("listBusinessFunctions", String.class, String.class, String.class, String.class).getAnnotation(RequireAuth.class));
        assertNull(WorkerGatewayController.class.getMethod("getBusinessFunctionSchema", String.class, String.class, String.class).getAnnotation(RequireAuth.class));
        assertNull(WorkerGatewayController.class.getMethod("invokeBusinessFunction", String.class, String.class, com.foggy.navigator.business.agent.model.form.WorkerGatewayInvokeForm.class).getAnnotation(RequireAuth.class));
    }
}
