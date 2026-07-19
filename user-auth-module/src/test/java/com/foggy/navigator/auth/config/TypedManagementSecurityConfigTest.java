package com.foggy.navigator.auth.config;

import com.foggy.navigator.auth.interceptor.AuthInterceptor;
import com.foggy.navigator.auth.interceptor.AuthorizationShadowInterceptor;
import com.foggy.navigator.auth.interceptor.TypedManagementAuthInterceptor;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TypedManagementSecurityConfigTest.ManagementTestController.class)
@Import({SecurityConfig.class, WebMvcConfig.class, TypedManagementSecurityConfigTest.ManagementTestController.class})
class TypedManagementSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthInterceptor authInterceptor;

    @MockitoBean
    private AuthorizationShadowInterceptor authorizationShadowInterceptor;

    @MockitoBean
    private TypedManagementAuthInterceptor typedManagementAuthInterceptor;

    @BeforeEach
    void resetController() {
        ManagementTestController.EXECUTIONS.set(0);
    }

    @Test
    void typedManagementNamespaceReachesTypedGuardAndDenyPreventsControllerExecution() throws Exception {
        doAnswer(invocation -> {
            HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            return false;
        }).when(typedManagementAuthInterceptor).preHandle(any(), any(), any());

        mockMvc.perform(post("/api/v1/management/v1/auth/exchange"))
                .andExpect(status().isUnauthorized());

        assertEquals(0, ManagementTestController.EXECUTIONS.get());
        verify(typedManagementAuthInterceptor).preHandle(any(), any(), any());
        verifyNoInteractions(authInterceptor, authorizationShadowInterceptor);
    }

    @Test
    void typedManagementNamespaceDoesNotNeedSpringJwtButStillRequiresTypedGuard() throws Exception {
        when(typedManagementAuthInterceptor.preHandle(any(), any(), any())).thenReturn(true);

        mockMvc.perform(post("/api/v1/management/v1/auth/exchange"))
                .andExpect(status().isOk());

        assertEquals(1, ManagementTestController.EXECUTIONS.get());
        verify(typedManagementAuthInterceptor).preHandle(any(), any(), any());
        verifyNoInteractions(authInterceptor, authorizationShadowInterceptor);
    }

    @Test
    void adjacentNonManagementPathRemainsBehindSpringSecurity() throws Exception {
        mockMvc.perform(post("/api/v1/management-private"))
                .andExpect(status().isForbidden());

        verify(typedManagementAuthInterceptor, never()).preHandle(any(), any(), any());
    }

    @RestController
    static class ManagementTestController {

        private static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @PostMapping("/api/v1/management/v1/auth/exchange")
        String exchange() {
            EXECUTIONS.incrementAndGet();
            return "ok";
        }

        @PostMapping("/api/v1/management-private")
        String privateRoute() {
            return "must-not-reach";
        }
    }
}
