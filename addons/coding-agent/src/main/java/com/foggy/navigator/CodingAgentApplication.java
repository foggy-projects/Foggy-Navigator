package com.foggy.navigator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.http.client.HttpClientAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;

/**
 * Coding Agent 应用启动类
 */
@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        HttpClientAutoConfiguration.class
})
public class CodingAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(CodingAgentApplication.class, args);
    }
}
