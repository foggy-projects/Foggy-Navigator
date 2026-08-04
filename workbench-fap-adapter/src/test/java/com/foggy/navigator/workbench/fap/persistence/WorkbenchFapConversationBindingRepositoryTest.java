package com.foggy.navigator.workbench.fap.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@ContextConfiguration(classes = WorkbenchFapConversationBindingRepositoryTest.TestApplication.class)
@EntityScan(basePackageClasses = WorkbenchFapConversationBindingEntity.class)
@EnableJpaRepositories(basePackageClasses = WorkbenchFapConversationBindingRepository.class)
class WorkbenchFapConversationBindingRepositoryTest {

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class TestApplication {}

    private final WorkbenchFapConversationBindingRepository bindings;

    @Autowired
    WorkbenchFapConversationBindingRepositoryTest(
            WorkbenchFapConversationBindingRepository bindings) {
        this.bindings = bindings;
    }

    @Test
    void persistsNewFapLaneWithoutAnyLegacySessionIdentity() {
        WorkbenchFapConversationBindingEntity binding =
                WorkbenchFapConversationBindingEntity.starting(
                        "conversation-1",
                        "owner-1",
                        "start-1",
                        "Focused task",
                        "worker-profile",
                        "workspace-profile",
                        null,
                        true);
        binding.activate(
                "execution-1",
                "task-1",
                "{\"mode\":\"NONE\"}",
                "{\"mode\":\"NONE\"}");
        bindings.saveAndFlush(binding);

        WorkbenchFapConversationBindingEntity loaded =
                bindings.findOwnedForUpdate("conversation-1", "owner-1").orElseThrow();

        assertThat(loaded.getExecutionLane()).isEqualTo("FAP_V1");
        assertThat(loaded.getExecutionId()).isEqualTo("execution-1");
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getUpdatedAt()).isNotNull();
        assertThat(bindings.findByOwnerUserIdAndStartRequestId("owner-1", "start-1"))
                .contains(loaded);
    }
}
