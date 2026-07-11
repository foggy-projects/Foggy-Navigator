package com.foggy.navigator.codereview.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.spi.config.GitProviderManager;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class GitLabMrClientContextTest {

    @Test
    void injectsDedicatedClientWhenAnotherRestTemplateIsPrimary() throws Exception {
        RestTemplate dedicatedClient = new RestTemplate();
        RestTemplate foreignPrimaryClient = new RestTemplate();

        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBean(GitProviderManager.class, () -> mock(GitProviderManager.class));
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean("codeReviewRestTemplate", RestTemplate.class, () -> dedicatedClient);
            context.registerBean("foreignPrimaryRestTemplate", RestTemplate.class,
                    () -> foreignPrimaryClient, definition -> definition.setPrimary(true));
            context.register(GitLabMrClient.class);
            context.refresh();

            GitLabMrClient client = context.getBean(GitLabMrClient.class);
            Field clientField = GitLabMrClient.class.getDeclaredField("codeReviewRestTemplate");
            clientField.setAccessible(true);

            assertThat(clientField.get(client)).isSameAs(dedicatedClient);
            assertThat(clientField.get(client)).isNotSameAs(foreignPrimaryClient);
        }
    }
}
