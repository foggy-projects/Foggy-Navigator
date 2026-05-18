package com.foggy.navigator.auth.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.form.ApiKeyCreateForm;
import com.foggy.navigator.common.form.UserUpdateForm;
import com.foggy.navigator.spi.auth.UserAuthService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户管理Controller
 */
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@RequireAuth
public class UserController {

    private final UserAuthService userAuthService;

    /**
     * 获取用户信息
     */
    @GetMapping("/{userId}")
    public RX<UserDTO> getUser(@PathVariable String userId) {
        return userAuthService.getUser(userId)
                .map(user -> {
                    requireSelfOrTenantAdmin(user);
                    return RX.ok(user);
                })
                .orElse(RX.failA("用户不存在"));
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/{userId}")
    public RX<Void> updateUser(@PathVariable String userId, @RequestBody UserUpdateForm form) {
        UserDTO target = requireUser(userId);
        requireSelfOrTenantAdmin(target);
        if (changesRolesOrStatus(form)) {
            requireTenantAdminForUser(target);
        }
        userAuthService.updateUser(userId, form);
        return RX.ok();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{userId}")
    public RX<Void> deleteUser(@PathVariable String userId) {
        UserDTO target = requireUser(userId);
        requireTenantAdminForUser(target);
        userAuthService.deleteUser(userId);
        return RX.ok();
    }

    /**
     * 查询所有用户（SUPER_ADMIN用，不区分租户）
     */
    @GetMapping
    @RequireAuth(roles = {"SUPER_ADMIN"})
    public RX<List<UserDTO>> listAllUsers() {
        List<UserDTO> users = userAuthService.listAllUsers();
        return RX.ok(users);
    }

    /**
     * 查询租户下的用户列表
     */
    @GetMapping("/tenant/{tenantId}")
    @RequireAuth(roles = {"TENANT_ADMIN"})
    public RX<List<UserDTO>> listUsersByTenant(@PathVariable String tenantId) {
        requireTenantAdminForTenant(tenantId);
        List<UserDTO> users = userAuthService.listUsersByTenant(tenantId);
        return RX.ok(users);
    }

    /**
     * 创建API Key
     */
    @PostMapping("/{userId}/api-keys")
    public RX<ApiKeyDTO> createApiKey(@PathVariable String userId, @RequestBody ApiKeyCreateForm form) {
        UserDTO target = requireUser(userId);
        requireSelfOrTenantAdmin(target);
        ApiKeyDTO apiKey = userAuthService.createApiKey(userId, form);
        return RX.ok(apiKey);
    }

    /**
     * 查询用户的API Key列表
     */
    @GetMapping("/{userId}/api-keys")
    public RX<List<ApiKeyDTO>> listApiKeys(@PathVariable String userId) {
        UserDTO target = requireUser(userId);
        requireSelfOrTenantAdmin(target);
        List<ApiKeyDTO> apiKeys = userAuthService.listApiKeysByUser(userId);
        return RX.ok(apiKeys);
    }

    /**
     * 撤销API Key
     */
    @DeleteMapping("/api-keys/{apiKeyId}")
    public RX<Void> revokeApiKey(@PathVariable String apiKeyId) {
        ApiKeyDTO apiKey = userAuthService.getApiKey(apiKeyId)
                .orElseThrow(() -> new IllegalArgumentException("API Key不存在"));
        UserDTO owner = requireUser(apiKey.getUserId());
        requireSelfOrTenantAdmin(owner);
        userAuthService.revokeApiKey(apiKeyId);
        return RX.ok();
    }

    private UserDTO requireUser(String userId) {
        return userAuthService.getUser(userId)
                .orElseThrow(() -> new IllegalArgumentException("用户不存在"));
    }

    private void requireSelfOrTenantAdmin(UserDTO target) {
        CurrentUser currentUser = requireCurrentUser();
        if (currentUser.isSuperAdmin() || isSelf(currentUser, target)) {
            return;
        }
        requireTenantAdminForUser(target);
    }

    private void requireTenantAdminForUser(UserDTO target) {
        CurrentUser currentUser = requireCurrentUser();
        if (currentUser.isSuperAdmin()) {
            return;
        }
        if (!currentUser.isTenantAdmin() || !sameTenant(currentUser, target.getTenantId())) {
            throw new SecurityException("无权限访问此接口");
        }
    }

    private void requireTenantAdminForTenant(String tenantId) {
        CurrentUser currentUser = requireCurrentUser();
        if (currentUser.isSuperAdmin()) {
            return;
        }
        if (!currentUser.isTenantAdmin() || !sameTenant(currentUser, tenantId)) {
            throw new SecurityException("无权限访问此接口");
        }
    }

    private CurrentUser requireCurrentUser() {
        CurrentUser currentUser = UserContext.getCurrentUser();
        if (currentUser == null) {
            throw new SecurityException("未登录，请先登录");
        }
        return currentUser;
    }

    private boolean isSelf(CurrentUser currentUser, UserDTO target) {
        return currentUser.getUserId() != null && currentUser.getUserId().equals(target.getId());
    }

    private boolean sameTenant(CurrentUser currentUser, String targetTenantId) {
        return currentUser.getTenantId() != null && currentUser.getTenantId().equals(targetTenantId);
    }

    private boolean changesRolesOrStatus(UserUpdateForm form) {
        return form != null && (form.getRoles() != null || form.getStatus() != null);
    }
}
