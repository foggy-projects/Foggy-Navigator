package com.foggy.navigator.launcher;

import com.foggy.navigator.common.authorization.AuthorizationDecisionAuditStore;
import com.foggy.navigator.common.entity.AuthorizationDecisionEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Exercises Spring Boot's real Actuator handler registration rather than only
 * inspecting the source-controlled route catalog. Discovery links are an
 * approved compatibility exception: the root is intentionally absent while
 * the health endpoint remains available and both retain sidecar observation.
 */
@SpringBootTest(
        classes = FogyNavigatorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = {
                "spring.main.allow-bean-definition-overriding=false",
                "spring.main.lazy-initialization=true",
                "spring.datasource.url=jdbc:h2:mem:gov001-actuator-discovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "navigator.database.startup-migrations.enabled=false",
                "navigator.deployment.navigator-instance-id=gov001-actuator-contract",
                "navigator.deployment.environment-profile=test",
                "system.root.password=gov001-test-root-password"
        }
)
@AutoConfigureMockMvc
class ActuatorDiscoveryRouteContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthorizationDecisionAuditStore decisionAuditStore;

    @Test
    void disabledDiscoveryRemovesRootLinksButKeepsHealthAndShadowsTheRoot404() throws Exception {
        List<AuthorizationDecisionEntity> before = decisionAuditStore.findByActionAndRoute(
                "actuator.discovery-links.read", "framework:get:/actuator");

        mockMvc.perform(get("/actuator").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound())
                .andExpect(content().string(not(containsString("\"_links\""))));

        List<AuthorizationDecisionEntity> after = decisionAuditStore.findByActionAndRoute(
                "actuator.discovery-links.read", "framework:get:/actuator");
        assertThat(after).hasSize(before.size() + 1);
        AuthorizationDecisionEntity audit = after.get(0);
        assertThat(audit.getLegacyDecision()).isEqualTo("DENY");
        assertThat(audit.getLegacyReasonCode()).isEqualTo("HTTP_STATUS_404");

        mockMvc.perform(get("/actuator/health").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
