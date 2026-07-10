package com.foggy.navigator.launcher;

import com.foggy.navigator.common.repository.NativeSubtaskStateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestTemplate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = FogyNavigatorApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.main.allow-bean-definition-overriding=false",
                "spring.main.lazy-initialization=true",
                "spring.datasource.url=jdbc:h2:mem:common-repository-ownership;MODE=MySQL;DB_CLOSE_DELAY=-1",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.jpa.hibernate.ddl-auto=create-drop",
                "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
                "navigator.database.startup-migrations.enabled=false",
                "system.root.password=test-root-password"
        }
)
class CommonRepositoryOwnershipContextTest {

    @Autowired
    private ApplicationContext applicationContext;

    @Test
    void commonRepositoryHasSingleOwnerWhenBeanOverridingIsDisabled() {
        assertThat(applicationContext.getBeanNamesForType(NativeSubtaskStateRepository.class))
                .containsExactly("nativeSubtaskStateRepository");
        RestTemplate metadataClient = applicationContext.getBean("metadataQueryRestTemplate", RestTemplate.class);
        assertThat(applicationContext.getBean("businessAgentRestTemplate", RestTemplate.class))
                .isNotSameAs(metadataClient);
        assertThat(applicationContext.getBean(RestTemplate.class)).isSameAs(metadataClient);
    }
}
