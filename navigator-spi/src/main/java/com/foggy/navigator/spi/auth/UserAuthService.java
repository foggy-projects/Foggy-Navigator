package com.foggy.navigator.spi.auth;

import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.LoginResultDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.form.*;

import java.util.List;
import java.util.Optional;

/**
 * 用户认证服务接口
 */
public interface UserAuthService {

    /**
     * 用户注册
     */
    String registerUser(UserRegisterForm form);

    /**
     * 用户登录
     */
    LoginResultDTO login(UserLoginForm form);

    /**
     * 根据Token获取用户信息
     */
    Optional<UserDTO> getUserByToken(String token);

    /**
     * 根据API Key获取用户信息
     */
    Optional<UserDTO> getUserByApiKey(String apiKey);

    /**
     * 获取用户信息
     */
    Optional<UserDTO> getUser(String userId);

    /**
     * 更新用户信息
     */
    void updateUser(String userId, UserUpdateForm form);

    /**
     * 删除用户
     */
    void deleteUser(String userId);

    /**
     * 查询租户下的用户列表
     */
    List<UserDTO> listUsersByTenant(String tenantId);

    /**
     * 查询所有未删除的用户（SUPER_ADMIN 用）
     */
    List<UserDTO> listAllUsers();

    /**
     * 创建API Key
     */
    ApiKeyDTO createApiKey(String userId, ApiKeyCreateForm form);

    /**
     * 根据 API Key ID 查询 API Key 元信息。
     *
     * 不返回明文 API Key，仅用于管理端权限判断和列表展示。
     */
    Optional<ApiKeyDTO> getApiKey(String apiKeyId);

    /**
     * 撤销API Key
     */
    void revokeApiKey(String apiKeyId);

    /**
     * 查询用户的API Key列表
     */
    List<ApiKeyDTO> listApiKeysByUser(String userId);

    /**
     * 生成内部服务 Token（短期 JWT），用于 CLI 子进程回调 Navigator API
     */
    String generateServiceToken(String userId);

    /**
     * 验证用户是否有指定角色
     */
    boolean hasRole(String userId, String role);

    /**
     * 验证用户是否属于指定租户
     */
    boolean belongsToTenant(String userId, String tenantId);
}
