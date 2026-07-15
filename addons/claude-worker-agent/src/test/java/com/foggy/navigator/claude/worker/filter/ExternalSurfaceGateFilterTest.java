package com.foggy.navigator.claude.worker.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.config.ExternalSurfaceProperties;
import com.foggy.navigator.claude.worker.controller.health.ExternalSurfaceHealthController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ExternalSurfaceGateFilterTest {

    private final ExternalSurfaceProperties properties = new ExternalSurfaceProperties();
    private final TestRoutesController routes = new TestRoutesController();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        routes.invocations.set(0);
        mockMvc = MockMvcBuilders
                .standaloneSetup(routes, new ExternalSurfaceHealthController(properties))
                .addFilters(new ExternalSurfaceGateFilter(properties, new ObjectMapper()))
                .build();
    }

    @Test
    void openApiIsFailClosedByDefaultBeforeControllerInvocation() throws Exception {
        assertThat(properties.isEnabled()).isFalse();

        mockMvc.perform(get("/api/v1/open/resource"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(ExternalSurfaceGateFilter.DISABLED_CODE))
                .andExpect(jsonPath("$.message").value("External API surface is disabled"));

        mockMvc.perform(get("/api/v1/open"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ExternalSurfaceGateFilter.DISABLED_CODE));

        assertThat(routes.invocations).hasValue(0);
    }

    @Test
    void explicitEnablementTransparentlyAllowsOpenApi() throws Exception {
        properties.setEnabled(true);

        mockMvc.perform(get("/api/v1/open/resource"))
                .andExpect(status().isOk())
                .andExpect(content().string("open"));

        assertThat(routes.invocations).hasValue(1);
    }

    @Test
    void nonOpenInternalRouteIsNotAffected() throws Exception {
        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isOk())
                .andExpect(content().string("internal"));

        mockMvc.perform(get("/api/v1/upstream-admin/client-apps"))
                .andExpect(status().isOk())
                .andExpect(content().string("control-plane"));

        mockMvc.perform(get("/api/v1/openish"))
                .andExpect(status().isOk())
                .andExpect(content().string("not-open-api"));

        assertThat(routes.invocations).hasValue(3);
    }

    @Test
    void matrixParametersCannotBypassTheClosedOpenApiGate() throws Exception {
        mockMvc.perform(get("/api/v1/open;source=test/resource"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ExternalSurfaceGateFilter.DISABLED_CODE));

        mockMvc.perform(get("/api/v1/open/resource;source=test"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ExternalSurfaceGateFilter.DISABLED_CODE));

        assertThat(routes.invocations).hasValue(0);
    }

    @Test
    void contextPathCannotBypassTheClosedOpenApiGate() throws Exception {
        mockMvc.perform(get("/navigator/api/v1/open/resource").contextPath("/navigator"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ExternalSurfaceGateFilter.DISABLED_CODE));

        assertThat(routes.invocations).hasValue(0);
    }

    @Test
    void encodedPathCannotBypassTheClosedOpenApiGate() throws Exception {
        mockMvc.perform(get(URI.create("/api/v1/%6fpen/resource")))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(ExternalSurfaceGateFilter.DISABLED_CODE));

        assertThat(routes.invocations).hasValue(0);
    }

    @Test
    void workerGatewayIsNotAffected() throws Exception {
        mockMvc.perform(get("/internal/worker-gateway/v1/status"))
                .andExpect(status().isOk())
                .andExpect(content().string("gateway"));

        assertThat(routes.invocations).hasValue(1);
    }

    @Test
    void healthDiagnosticIsAlwaysAvailableAndDoesNotClaimProviderOrProductionReadiness() throws Exception {
        mockMvc.perform(get("/api/v1/health/external-surface"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.mode").value("internal-only"))
                .andExpect(jsonPath("$.surfaceReady").value(false))
                .andExpect(jsonPath("$.readinessScope").value("platform-routing-only"))
                .andExpect(jsonPath("$.providerReadinessAssessed").value(false))
                .andExpect(jsonPath("$.productionReady").value(false));

        properties.setEnabled(true);

        mockMvc.perform(get("/api/v1/health/external-surface"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.mode").value("external-enabled"))
                .andExpect(jsonPath("$.surfaceReady").value(true))
                .andExpect(jsonPath("$.providerReadinessAssessed").value(false))
                .andExpect(jsonPath("$.productionReady").value(false));
    }

    @RestController
    static class TestRoutesController {

        private final AtomicInteger invocations = new AtomicInteger();

        @GetMapping("/api/v1/open")
        String openRoot() {
            invocations.incrementAndGet();
            return "open-root";
        }

        @GetMapping("/api/v1/open/resource")
        String open() {
            invocations.incrementAndGet();
            return "open";
        }

        @GetMapping("/api/v1/tasks")
        String internal() {
            invocations.incrementAndGet();
            return "internal";
        }

        @GetMapping("/api/v1/upstream-admin/client-apps")
        String controlPlane() {
            invocations.incrementAndGet();
            return "control-plane";
        }

        @GetMapping("/api/v1/openish")
        String similarlyNamedInternalRoute() {
            invocations.incrementAndGet();
            return "not-open-api";
        }

        @GetMapping("/internal/worker-gateway/v1/status")
        String workerGateway() {
            invocations.incrementAndGet();
            return "gateway";
        }
    }
}
