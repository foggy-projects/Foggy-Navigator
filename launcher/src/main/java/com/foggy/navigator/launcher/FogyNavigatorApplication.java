package com.foggy.navigator.launcher;

import com.foggy.navigator.auth.config.AuthAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Coding Agent 应用启动类
 */
@SpringBootApplication(exclude = {
})
public class FogyNavigatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(FogyNavigatorApplication.class, args);
    }


}
