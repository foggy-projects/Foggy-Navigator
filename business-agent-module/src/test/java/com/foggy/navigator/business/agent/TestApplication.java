package com.foggy.navigator.business.agent;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EnableJpaRepositories(basePackages = "com.foggy.navigator.business.agent.repository")
@EntityScan(basePackages = {
        "com.foggy.navigator.business.agent.model.entity",
        "com.foggy.navigator.common.entity"
})
public class TestApplication {
}
