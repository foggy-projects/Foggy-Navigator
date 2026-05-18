package com.foggy.navigator.auth.service;

import com.foggy.navigator.auth.repository.ApiKeyRepository;
import com.foggy.navigator.auth.repository.UserRepository;
import com.foggy.navigator.common.dto.ApiKeyDTO;
import com.foggy.navigator.common.dto.LoginResultDTO;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.common.enums.UserRole;
import com.foggy.navigator.common.form.ApiKeyCreateForm;
import com.foggy.navigator.common.form.UserLoginForm;
import com.foggy.navigator.common.form.UserRegisterForm;
import com.foggy.navigator.spi.auth.UserAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * UserAuthService 单元测试
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UserAuthServiceTest {

    @Autowired
    private UserAuthService userAuthService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Test
    void testRegisterAndLogin() {
        // 注册用户
        UserRegisterForm registerForm = new UserRegisterForm();
        registerForm.setTenantId("tenant-001");
        registerForm.setUsername("testuser");
        registerForm.setPassword("password123");
        registerForm.setEmail("test@example.com");
        registerForm.setDisplayName("Test User");
        registerForm.setRoles(UserRole.DEVELOPER.name());

        String userId = userAuthService.registerUser(registerForm);
        assertNotNull(userId);

        // 登录
        UserLoginForm loginForm = new UserLoginForm();
        loginForm.setUsername("testuser");
        loginForm.setPassword("password123");

        LoginResultDTO loginResult = userAuthService.login(loginForm);
        assertNotNull(loginResult);
        assertNotNull(loginResult.getToken());
        assertEquals("Bearer", loginResult.getTokenType());
        assertNotNull(loginResult.getUser());
        assertEquals("testuser", loginResult.getUser().getUsername());
    }

    @Test
    void testGetUserByToken() {
        // 先注册并登录
        String userId = createTestUser("tokenuser", "password");
        UserLoginForm loginForm = new UserLoginForm();
        loginForm.setUsername("tokenuser");
        loginForm.setPassword("password");

        LoginResultDTO loginResult = userAuthService.login(loginForm);
        String token = loginResult.getToken();

        // 通过Token获取用户
        Optional<UserDTO> userOpt = userAuthService.getUserByToken(token);
        assertTrue(userOpt.isPresent());
        assertEquals("tokenuser", userOpt.get().getUsername());
    }

    @Test
    void testCreateAndListApiKeys() {
        // 创建用户
        String userId = createTestUser("apikeyuser", "password");

        // 创建API Key
        ApiKeyCreateForm form = new ApiKeyCreateForm();
        form.setName("Test API Key");

        ApiKeyDTO apiKey = userAuthService.createApiKey(userId, form);
        assertNotNull(apiKey);
        assertNotNull(apiKey.getApiKey());
        assertTrue(apiKey.getApiKey().startsWith("sk-"));

        // 查询API Key列表
        List<ApiKeyDTO> apiKeys = userAuthService.listApiKeysByUser(userId);
        assertEquals(1, apiKeys.size());
        assertNull(apiKeys.get(0).getApiKey()); // 列表中不返回明文
        assertNotNull(apiKeys.get(0).getMaskedApiKey());
    }

    @Test
    void testGetApiKeyDoesNotReturnPlaintext() {
        String userId = createTestUser("apikeymetadatasuser", "password");
        ApiKeyCreateForm form = new ApiKeyCreateForm();
        form.setName("Metadata Test Key");
        ApiKeyDTO created = userAuthService.createApiKey(userId, form);

        Optional<ApiKeyDTO> fetched = userAuthService.getApiKey(created.getId());

        assertTrue(fetched.isPresent());
        assertEquals(userId, fetched.get().getUserId());
        assertNull(fetched.get().getApiKey());
        assertNotNull(fetched.get().getMaskedApiKey());
    }

    @Test
    void testGetUserByApiKey() {
        // 创建用户和API Key
        String userId = createTestUser("apikeyauthuser", "password");

        ApiKeyCreateForm form = new ApiKeyCreateForm();
        form.setName("Auth Test Key");
        ApiKeyDTO apiKey = userAuthService.createApiKey(userId, form);

        // 通过API Key获取用户
        Optional<UserDTO> userOpt = userAuthService.getUserByApiKey(apiKey.getApiKey());
        assertTrue(userOpt.isPresent());
        assertEquals("apikeyauthuser", userOpt.get().getUsername());
    }

    @Test
    void testRevokeApiKey() {
        // 创建用户和API Key
        String userId = createTestUser("revokeuser", "password");
        ApiKeyCreateForm form = new ApiKeyCreateForm();
        form.setName("Revoke Test Key");
        ApiKeyDTO apiKey = userAuthService.createApiKey(userId, form);

        // 撤销API Key
        userAuthService.revokeApiKey(apiKey.getId());

        // 验证无法再使用
        Optional<UserDTO> userOpt = userAuthService.getUserByApiKey(apiKey.getApiKey());
        assertFalse(userOpt.isPresent());
    }

    @Test
    void testHasRole() {
        String userId = createTestUser("roleuser", "password");

        assertTrue(userAuthService.hasRole(userId, UserRole.DEVELOPER.name()));
        assertFalse(userAuthService.hasRole(userId, UserRole.SUPER_ADMIN.name()));
    }

    @Test
    void testBelongsToTenant() {
        String userId = createTestUser("tenantuser", "password");

        assertTrue(userAuthService.belongsToTenant(userId, "tenant-001"));
        assertFalse(userAuthService.belongsToTenant(userId, "tenant-002"));
    }

    /**
     * 辅助方法：创建测试用户
     */
    private String createTestUser(String username, String password) {
        UserRegisterForm form = new UserRegisterForm();
        form.setTenantId("tenant-001");
        form.setUsername(username);
        form.setPassword(password);
        form.setEmail(username + "@example.com");
        form.setRoles(UserRole.DEVELOPER.name());
        return userAuthService.registerUser(form);
    }
}
