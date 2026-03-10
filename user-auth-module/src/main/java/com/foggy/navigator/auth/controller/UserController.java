package com.foggy.navigator.auth.controller;

import com.foggy.navigator.common.dto.ApiKeyDTO;
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
public class UserController {

    private final UserAuthService userAuthService;

    /**
     * 获取用户信息
     */
    @GetMapping("/{userId}")
    public RX<UserDTO> getUser(@PathVariable String userId) {
        return userAuthService.getUser(userId)
                .map(RX::ok)
                .orElse(RX.failA("用户不存在"));
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/{userId}")
    public RX<Void> updateUser(@PathVariable String userId, @RequestBody UserUpdateForm form) {
        userAuthService.updateUser(userId, form);
        return RX.ok();
    }

    /**
     * 删除用户
     */
    @DeleteMapping("/{userId}")
    public RX<Void> deleteUser(@PathVariable String userId) {
        userAuthService.deleteUser(userId);
        return RX.ok();
    }

    /**
     * 查询所有用户（SUPER_ADMIN用，不区分租户）
     */
    @GetMapping
    public RX<List<UserDTO>> listAllUsers() {
        List<UserDTO> users = userAuthService.listAllUsers();
        return RX.ok(users);
    }

    /**
     * 查询租户下的用户列表
     */
    @GetMapping("/tenant/{tenantId}")
    public RX<List<UserDTO>> listUsersByTenant(@PathVariable String tenantId) {
        List<UserDTO> users = userAuthService.listUsersByTenant(tenantId);
        return RX.ok(users);
    }

    /**
     * 创建API Key
     */
    @PostMapping("/{userId}/api-keys")
    public RX<ApiKeyDTO> createApiKey(@PathVariable String userId, @RequestBody ApiKeyCreateForm form) {
        ApiKeyDTO apiKey = userAuthService.createApiKey(userId, form);
        return RX.ok(apiKey);
    }

    /**
     * 查询用户的API Key列表
     */
    @GetMapping("/{userId}/api-keys")
    public RX<List<ApiKeyDTO>> listApiKeys(@PathVariable String userId) {
        List<ApiKeyDTO> apiKeys = userAuthService.listApiKeysByUser(userId);
        return RX.ok(apiKeys);
    }

    /**
     * 撤销API Key
     */
    @DeleteMapping("/api-keys/{apiKeyId}")
    public RX<Void> revokeApiKey(@PathVariable String apiKeyId) {
        userAuthService.revokeApiKey(apiKeyId);
        return RX.ok();
    }
}
