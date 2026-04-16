package com.foggy.navigator.langgraph.worker.config;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@AutoConfiguration
@ComponentScan(basePackages = {
        "com.foggy.navigator.langgraph.worker.service",
        "com.foggy.navigator.langgraph.worker.controller",
        "com.foggy.navigator.langgraph.worker.client",
        "com.foggy.navigator.langgraph.worker.adapter"
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
}
