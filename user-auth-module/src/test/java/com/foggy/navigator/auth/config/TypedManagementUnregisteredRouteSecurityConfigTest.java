package com.foggy.navigator.auth.config;

import com.foggy.navigator.auth.interceptor.AuthInterceptor;
import com.foggy.navigator.auth.interceptor.AuthorizationShadowInterceptor;
import com.foggy.navigator.auth.interceptor.TypedManagementAuthInterceptor;
import com.foggy.navigator.common.authorization.AuthorizationReasonCode;
import com.foggy.navigator.common.authorization.AuthorizationRouteCatalog;
import com.foggy.navigator.common.authorization.ManagementActionSetRegistry;
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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises the real typed-management interceptor through the MVC and Spring
 * Security chains.  The handler is deliberately mapped but omitted from the
 * fixed action set and route manifest contract.
 */
@WebMvcTest(controllers = TypedManagementUnregisteredRouteSecurityConfigTest.UnregisteredManagementController.class)
@Import({
        SecurityConfig.class,
        WebMvcConfig.class,
        TypedManagementAuthInterceptor.class
})
class TypedManagementUnregisteredRouteSecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthInterceptor authInterceptor;

    @MockitoBean
    private AuthorizationShadowInterceptor authorizationShadowInterceptor;

    @MockitoBean
    private AuthorizationRouteCatalog routeCatalog;

    @MockitoBean
    private ManagementActionSetRegistry managementActionSetRegistry;

    @BeforeEach
    void resetController() {
        UnregisteredManagementController.EXECUTIONS.set(0);
    }

    @Test
    void mappedButUnregisteredManagementRouteIsDeniedBeforeControllerExecution() throws Exception {
        mockMvc.perform(post("/api/v1/management/v1/auth/not-registered"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasonCode")
                        .value(AuthorizationReasonCode.AUTHZ_ACTION_UNREGISTERED.name()));

        assertEquals(0, UnregisteredManagementController.EXECUTIONS.get());
        verifyNoInteractions(routeCatalog, managementActionSetRegistry, authInterceptor, authorizationShadowInterceptor);
    }

    @RestController
    static class UnregisteredManagementController {

        private static final AtomicInteger EXECUTIONS = new AtomicInteger();

        @PostMapping("/api/v1/management/v1/auth/not-registered")
        String notRegistered() {
            EXECUTIONS.incrementAndGet();
            return "must-not-reach";
        }
    }
}
