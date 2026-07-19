package com.foggy.navigator.launcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.endpoint.web.servlet.WebMvcEndpointHandlerMapping;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Locks the Owner-approved compatibility change for the framework-owned
 * Actuator discovery links endpoint. This test deliberately uses the real
 * launcher application.yml and does not override the discovery property.
 */
@SpringBootTest(
        classes = FogyNavigatorApplication.class,
        properties = {
                "spring.main.lazy-initialization=true",
                "spring.datasource.url=jdbc:h2:mem:gov001-actuator-discovery;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "navigator.database.startup-migrations.enabled=false",
                "system.root.password=gov001-test-root-password"
        }
)
@AutoConfigureMockMvc
class ActuatorDiscoveryContractTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private WebMvcEndpointHandlerMapping actuatorHandlerMapping;

    @Autowired
    private Environment environment;

    @Test
    void disabledDiscoveryRemovesRootLinksWhileHealthRemainsAvailable() throws Exception {
        assertThat(environment.getProperty("management.endpoints.web.discovery.enabled")).isEqualTo("false");

        mockMvc.perform(get("/actuator"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        Set<String> actuatorPatterns = actuatorHandlerMapping.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(actuatorPatterns).doesNotContain("/actuator");
        assertThat(actuatorPatterns).contains("/actuator/health");
    }
}
