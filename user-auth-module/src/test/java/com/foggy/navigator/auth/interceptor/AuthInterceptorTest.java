package com.foggy.navigator.auth.interceptor;

import com.foggy.navigator.auth.util.JwtUtil;
import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.dto.UserDTO;
import com.foggy.navigator.spi.auth.UserAuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * AuthInterceptor 单元测试 — L1
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock private JwtUtil jwtUtil;
    @Mock private UserAuthService userAuthService;
    @Mock private HttpServletRequest request;
    @Mock private HttpServletResponse response;

    @InjectMocks private AuthInterceptor interceptor;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // ---- Bearer Token 认证 ----

    @Test
    void preHandle_bearerToken_setsUserContext() {
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtUtil.validateToken("valid-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("valid-token")).thenReturn("user-1");
        when(jwtUtil.getUsernameFromToken("valid-token")).thenReturn("alice");
        when(jwtUtil.getTenantIdFromToken("valid-token")).thenReturn("t1");
        when(jwtUtil.getRolesFromToken("valid-token")).thenReturn("DEVELOPER");
        when(jwtUtil.needsRenewal("valid-token")).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        CurrentUser user = UserContext.getCurrentUser();
        assertNotNull(user);
        assertEquals("user-1", user.getUserId());
        assertEquals("alice", user.getUsername());
        verify(request).setAttribute("userId", "user-1");
        verify(request).setAttribute("username", "alice");
        verify(request).setAttribute("tenantId", "t1");
        verify(request).setAttribute("roles", "DEVELOPER");
    }

    @Test
    void preHandle_invalidBearerToken_noUserContext() {
        when(request.getHeader("Authorization")).thenReturn("Bearer bad-token");
        when(jwtUtil.validateToken("bad-token")).thenReturn(false);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result); // 拦截器不阻止请求
        assertNull(UserContext.getCurrentUser());
    }

    @Test
    void preHandle_bearerToken_renewalSetsHeader() {
        when(request.getHeader("Authorization")).thenReturn("Bearer expiring-token");
        when(jwtUtil.validateToken("expiring-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("expiring-token")).thenReturn("user-1");
        when(jwtUtil.getUsernameFromToken("expiring-token")).thenReturn("alice");
        when(jwtUtil.getTenantIdFromToken("expiring-token")).thenReturn("t1");
        when(jwtUtil.getRolesFromToken("expiring-token")).thenReturn("ADMIN");
        when(jwtUtil.needsRenewal("expiring-token")).thenReturn(true);
        when(jwtUtil.renewToken("expiring-token")).thenReturn("new-token");

        interceptor.preHandle(request, response, new Object());

        verify(response).setHeader("X-New-Token", "new-token");
    }

    // ---- Query Param Token（SSE 场景）----

    @Test
    void preHandle_queryParamToken_setsUserContext() {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("token")).thenReturn("sse-token");
        when(jwtUtil.validateToken("sse-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("sse-token")).thenReturn("user-2");
        when(jwtUtil.getUsernameFromToken("sse-token")).thenReturn("bob");
        when(jwtUtil.getTenantIdFromToken("sse-token")).thenReturn("t2");
        when(jwtUtil.getRolesFromToken("sse-token")).thenReturn("VIEWER");

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertEquals("user-2", UserContext.getCurrentUser().getUserId());
    }

    // ---- API Key 认证 ----

    @Test
    void preHandle_apiKey_setsUserContext() {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("token")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn("sk-abc123");

        UserDTO dto = new UserDTO();
        dto.setId("user-3");
        dto.setUsername("charlie");
        dto.setTenantId("t3");
        dto.setRoles("DEVELOPER");
        when(userAuthService.getUserByApiKey("sk-abc123")).thenReturn(Optional.of(dto));

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertEquals("user-3", UserContext.getCurrentUser().getUserId());
    }

    @Test
    void preHandle_invalidApiKey_noUserContext() {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("token")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn("sk-invalid");
        when(userAuthService.getUserByApiKey("sk-invalid")).thenReturn(Optional.empty());

        interceptor.preHandle(request, response, new Object());

        assertNull(UserContext.getCurrentUser());
    }

    // ---- 无认证信息 ----

    @Test
    void preHandle_noAuthInfo_noUserContext() {
        when(request.getHeader("Authorization")).thenReturn(null);
        when(request.getParameter("token")).thenReturn(null);
        when(request.getHeader("X-API-Key")).thenReturn(null);

        boolean result = interceptor.preHandle(request, response, new Object());

        assertTrue(result);
        assertNull(UserContext.getCurrentUser());
    }

    // ---- 优先级：Bearer > Query Param > API Key ----

    @Test
    void preHandle_bearerTakesPrecedenceOverApiKey() {
        when(request.getHeader("Authorization")).thenReturn("Bearer jwt-token");
        when(jwtUtil.validateToken("jwt-token")).thenReturn(true);
        when(jwtUtil.getUserIdFromToken("jwt-token")).thenReturn("jwt-user");
        when(jwtUtil.getUsernameFromToken("jwt-token")).thenReturn("jwt-name");
        when(jwtUtil.getTenantIdFromToken("jwt-token")).thenReturn(null);
        when(jwtUtil.getRolesFromToken("jwt-token")).thenReturn(null);
        when(jwtUtil.needsRenewal("jwt-token")).thenReturn(false);

        interceptor.preHandle(request, response, new Object());

        assertEquals("jwt-user", UserContext.getCurrentUser().getUserId());
        // API Key 路径不应被调用
        verify(userAuthService, never()).getUserByApiKey(any());
    }

    // ---- afterCompletion 清理 ----

    @Test
    void afterCompletion_clearsUserContext() {
        UserContext.setCurrentUser(CurrentUser.builder().userId("temp").build());
        assertNotNull(UserContext.getCurrentUser());

        interceptor.afterCompletion(request, response, new Object(), null);

        assertNull(UserContext.getCurrentUser());
    }
}
