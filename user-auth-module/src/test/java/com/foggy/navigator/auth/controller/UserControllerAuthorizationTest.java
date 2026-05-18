package com.foggy.navigator.auth.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.enums.UserRole;
import com.foggy.navigator.common.enums.UserStatus;
import com.foggy.navigator.common.form.ApiKeyCreateForm;
import com.foggy.navigator.common.form.UserUpdateForm;
import com.foggy.navigator.spi.auth.UserAuthService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * UserController 权限回归测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class UserControllerAuthorizationTest {

    @Mock
    private UserAuthService userAuthService;

    @InjectMocks
    private UserController controller;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void userController_requiresAuthenticationAtClassLevel() {
        assertNotNull(UserController.class.getAnnotation(RequireAuth.class));
    }

    @Test
    void listAllUsers_requiresSuperAdmin() throws NoSuchMethodException {
        Method method = UserController.class.getMethod("listAllUsers");
        RequireAuth annotation = method.getAnnotation(RequireAuth.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.roles()).contains(UserRole.SUPER_ADMIN.name()));
    }

    @Test
    void listUsersByTenant_requiresTenantAdmin() throws NoSuchMethodException {
        Method method = UserController.class.getMethod("listUsersByTenant", String.class);
        RequireAuth annotation = method.getAnnotation(RequireAuth.class);
        assertNotNull(annotation);
        assertTrue(Arrays.asList(annotation.roles()).contains(UserRole.TENANT_ADMIN.name()));
    }

    @Test
    void anonymousCannotReadUser() {
        when(userAuthService.getUser("user-1")).thenReturn(Optional.of(user("user-1", "tenant-1")));

        assertThrows(SecurityException.class, () -> controller.getUser("user-1"));
    }

    @Test
    void developerCanCreateOwnApiKey() {
        setCurrentUser("user-1", "tenant-1", UserRole.DEVELOPER.name());
        when(userAuthService.getUser("user-1")).thenReturn(Optional.of(user("user-1", "tenant-1")));
        when(userAuthService.createApiKey(eq("user-1"), any(ApiKeyCreateForm.class))).thenReturn(new ApiKeyDTO());

        controller.createApiKey("user-1", new ApiKeyCreateForm());

        verify(userAuthService).createApiKey(eq("user-1"), any(ApiKeyCreateForm.class));
    }

    @Test
    void developerCannotCreateApiKeyForAnotherUser() {
        setCurrentUser("user-1", "tenant-1", UserRole.DEVELOPER.name());
        when(userAuthService.getUser("user-2")).thenReturn(Optional.of(user("user-2", "tenant-1")));

        assertThrows(SecurityException.class, () -> controller.createApiKey("user-2", new ApiKeyCreateForm()));
        verify(userAuthService, never()).createApiKey(anyString(), any());
    }

    @Test
    void tenantAdminCanCreateApiKeyForSameTenantUser() {
        setCurrentUser("admin-1", "tenant-1", UserRole.TENANT_ADMIN.name());
        when(userAuthService.getUser("user-2")).thenReturn(Optional.of(user("user-2", "tenant-1")));
        when(userAuthService.createApiKey(eq("user-2"), any(ApiKeyCreateForm.class))).thenReturn(new ApiKeyDTO());

        controller.createApiKey("user-2", new ApiKeyCreateForm());

        verify(userAuthService).createApiKey(eq("user-2"), any(ApiKeyCreateForm.class));
    }

    @Test
    void tenantAdminCannotManageOtherTenantUser() {
        setCurrentUser("admin-1", "tenant-1", UserRole.TENANT_ADMIN.name());
        when(userAuthService.getUser("user-2")).thenReturn(Optional.of(user("user-2", "tenant-2")));

        assertThrows(SecurityException.class, () -> controller.listApiKeys("user-2"));
        verify(userAuthService, never()).listApiKeysByUser(anyString());
    }

    @Test
    void selfUpdateCannotChangeRolesOrStatus() {
        setCurrentUser("user-1", "tenant-1", UserRole.DEVELOPER.name());
        when(userAuthService.getUser("user-1")).thenReturn(Optional.of(user("user-1", "tenant-1")));
        UserUpdateForm form = new UserUpdateForm();
        form.setRoles(UserRole.TENANT_ADMIN.name());

        assertThrows(SecurityException.class, () -> controller.updateUser("user-1", form));
        verify(userAuthService, never()).updateUser(anyString(), any());
    }

    @Test
    void revokeApiKeyChecksOwnerBeforeRevoking() {
        setCurrentUser("user-1", "tenant-1", UserRole.DEVELOPER.name());
        ApiKeyDTO apiKey = new ApiKeyDTO();
        apiKey.setId("key-1");
        apiKey.setUserId("user-2");
        when(userAuthService.getApiKey("key-1")).thenReturn(Optional.of(apiKey));
        when(userAuthService.getUser("user-2")).thenReturn(Optional.of(user("user-2", "tenant-1")));

        assertThrows(SecurityException.class, () -> controller.revokeApiKey("key-1"));
        verify(userAuthService, never()).revokeApiKey(anyString());
    }

    @Test
    void tenantAdminCannotListOtherTenantUsers() {
        setCurrentUser("admin-1", "tenant-1", UserRole.TENANT_ADMIN.name());

        assertThrows(SecurityException.class, () -> controller.listUsersByTenant("tenant-2"));
        verify(userAuthService, never()).listUsersByTenant(anyString());
    }

    @Test
    void superAdminCanListAnyTenantUsers() {
        setCurrentUser("root", "root-tenant", UserRole.SUPER_ADMIN.name());

        controller.listUsersByTenant("tenant-2");

        verify(userAuthService).listUsersByTenant("tenant-2");
    }

    private void setCurrentUser(String userId, String tenantId, String roles) {
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(userId)
                .username(userId)
                .tenantId(tenantId)
                .roles(roles)
                .build());
    }

    private UserDTO user(String userId, String tenantId) {
        UserDTO user = new UserDTO();
        user.setId(userId);
        user.setUsername(userId);
        user.setTenantId(tenantId);
        user.setStatus(UserStatus.ACTIVE);
        return user;
    }
}
