package com.foggy.navigator.auth.config;

import com.foggy.navigator.auth.interceptor.AuthInterceptor;
import com.foggy.navigator.auth.interceptor.AuthorizationShadowInterceptor;
import com.foggy.navigator.auth.interceptor.TypedManagementAuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.config.annotation.InterceptorRegistration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.handler.MappedInterceptor;
import org.springframework.web.util.ServletRequestPathUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebMvcConfigTest {

    @Test
    void frameworkIngressMappedInterceptorIsLimitedToActuatorAndSshHandshakePaths() {
        AuthorizationShadowInterceptor shadowInterceptor = mock(AuthorizationShadowInterceptor.class);
        WebMvcConfig config = new WebMvcConfig(
                mock(AuthInterceptor.class), shadowInterceptor, mock(TypedManagementAuthInterceptor.class));

        MappedInterceptor mappedInterceptor = config.authorizationShadowFrameworkIngressInterceptor();

        assertSame(shadowInterceptor, mappedInterceptor.getInterceptor());
        assertTrue(mappedInterceptor.matches(request("/actuator")));
        assertTrue(mappedInterceptor.matches(request("/actuator/health/liveness")));
        assertTrue(mappedInterceptor.matches(request("/api/v1/ssh/session-42/ws")));
        assertFalse(mappedInterceptor.matches(request("/api/v1/health/external-surface")));
        assertFalse(mappedInterceptor.matches(request("/api/v1/ssh/session-42/ws/extra")));
    }

    @Test
    void shadowInterceptorAlsoObservesTheRegisteredNonApiDiagnosticShareIngress() {
        AuthorizationShadowInterceptor shadowInterceptor = mock(AuthorizationShadowInterceptor.class);
        TypedManagementAuthInterceptor typedManagementAuthInterceptor = mock(TypedManagementAuthInterceptor.class);
        InterceptorRegistry registry = mock(InterceptorRegistry.class);
        InterceptorRegistration authRegistration = mock(InterceptorRegistration.class);
        InterceptorRegistration managementRegistration = mock(InterceptorRegistration.class);
        InterceptorRegistration shadowRegistration = mock(InterceptorRegistration.class);
        when(registry.addInterceptor(org.mockito.ArgumentMatchers.any())).thenReturn(
                authRegistration, managementRegistration, shadowRegistration);
        when(authRegistration.addPathPatterns("/api/**")).thenReturn(authRegistration);
        when(authRegistration.excludePathPatterns(org.mockito.ArgumentMatchers.<String>any())).thenReturn(authRegistration);
        when(managementRegistration.addPathPatterns("/api/v1/management/v1/**")).thenReturn(managementRegistration);
        when(shadowRegistration.addPathPatterns(
                "/api/**", "/internal/worker-gateway/v1/**", "/diagnostic-share/**"))
                .thenReturn(shadowRegistration);
        when(shadowRegistration.excludePathPatterns("/api/v1/management/v1/**")).thenReturn(shadowRegistration);

        new WebMvcConfig(mock(AuthInterceptor.class), shadowInterceptor, typedManagementAuthInterceptor)
                .addInterceptors(registry);

        verify(authRegistration).excludePathPatterns(
                "/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/health/**", "/api/v1/management/v1/**");
        verify(managementRegistration).addPathPatterns("/api/v1/management/v1/**");
        verify(shadowRegistration).addPathPatterns(
                "/api/**", "/internal/worker-gateway/v1/**", "/diagnostic-share/**");
        verify(shadowRegistration).excludePathPatterns("/api/v1/management/v1/**");
    }

    private static MockHttpServletRequest request(String path) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", path);
        ServletRequestPathUtils.parseAndCache(request);
        return request;
    }
}
