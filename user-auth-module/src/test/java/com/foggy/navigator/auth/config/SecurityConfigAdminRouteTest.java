package com.foggy.navigator.auth.config;

import com.foggy.navigator.auth.interceptor.AuthInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = SecurityConfigAdminRouteTest.AdminRouteController.class)
@Import({SecurityConfig.class, SecurityConfigAdminRouteTest.AdminRouteController.class})
class SecurityConfigAdminRouteTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AuthInterceptor authInterceptor;

    @Test
    void adminModelConfigRepairRoute_isPassedThroughToControllerAuthGuard() throws Exception {
        mockMvc.perform(post("/api/v1/admin/model-configs/model-1/repair-owner")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void unlistedAdminRoute_stillRequiresSpringSecurityAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/admin/private-repair"))
                .andExpect(status().isForbidden());
    }

    @RestController
    static class AdminRouteController {

        @PostMapping("/api/v1/admin/model-configs/{modelConfigId}/repair-owner")
        String repairModelConfigOwner() {
            return "ok";
        }

        @PostMapping("/api/v1/admin/private-repair")
        String privateRepair() {
            return "ok";
        }
    }
}
