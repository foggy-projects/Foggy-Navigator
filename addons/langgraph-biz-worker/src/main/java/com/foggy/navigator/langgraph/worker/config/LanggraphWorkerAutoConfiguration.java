package com.foggy.navigator.langgraph.worker.config;

import com.foggy.navigator.langgraph.worker.client.WorkerGatewayClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.langgraph.worker.service",
        "com.foggy.navigator.langgraph.worker.controller",
        "com.foggy.navigator.langgraph.worker.client",
        "com.foggy.navigator.langgraph.worker.adapter",
        "com.foggy.navigator.langgraph.worker.tool"
})
@EntityScan(basePackages = {
        "com.foggy.navigator.langgraph.worker.model.entity",
        "com.foggy.navigator.common.entity"
})
@EnableJpaRepositories(basePackages = {
        "com.foggy.navigator.langgraph.worker.repository",
        "com.foggy.navigator.common.repository"
})
public class LanggraphWorkerAutoConfiguration {

    @Bean
    public WorkerGatewayClient workerGatewayClient(
            @Value("${navigator.worker-gateway.base-url:http://localhost:8080}") String gatewayBaseUrl) {
        return new WorkerGatewayClient(gatewayBaseUrl);
    }
}
