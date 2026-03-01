package com.foggy.navigator.monitoring.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * Auto-configuration for the monitoring module.
 * Enabled by default when spring.rabbitmq.host is set.
 * Can be explicitly disabled with foggy.monitoring.enabled=false.
 */
@Slf4j
@AutoConfiguration
@ConditionalOnProperty(name = "foggy.monitoring.enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "com.foggy.navigator.monitoring")
@EntityScan(basePackages = "com.foggy.navigator.monitoring.model.entity")
@EnableJpaRepositories(basePackages = "com.foggy.navigator.monitoring.repository")
public class MonitoringAutoConfiguration {

    // -- Exchange declaration -------------------------------------------------

    @Bean
    public TopicExchange foggyEventsExchange() {
        log.info("Declaring RabbitMQ exchange: foggy.events (topic, durable)");
        return ExchangeBuilder.topicExchange("foggy.events").durable(true).build();
    }

    // -- Queue declarations ---------------------------------------------------

    @Bean
    public Queue foggyMonitorLogsQueue() {
        return QueueBuilder.durable("foggy.monitor.logs").build();
    }

    @Bean
    public Queue foggyMonitorHeartbeatsQueue() {
        // TTL 120s — heartbeat messages expire if not consumed
        return QueueBuilder.durable("foggy.monitor.heartbeats")
                .ttl(120_000)
                .build();
    }

    // -- Bindings -------------------------------------------------------------

    @Bean
    public Binding logsBinding(Queue foggyMonitorLogsQueue, TopicExchange foggyEventsExchange) {
        return BindingBuilder.bind(foggyMonitorLogsQueue)
                .to(foggyEventsExchange)
                .with("monitor.log.#");
    }

    @Bean
    public Binding heartbeatsBinding(Queue foggyMonitorHeartbeatsQueue, TopicExchange foggyEventsExchange) {
        return BindingBuilder.bind(foggyMonitorHeartbeatsQueue)
                .to(foggyEventsExchange)
                .with("monitor.heartbeat.#");
    }
}
