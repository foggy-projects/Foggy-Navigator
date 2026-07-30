package com.foggy.navigator.business.agent.service;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Data
@Component
@ConfigurationProperties(prefix = "navigator.runtime-audit")
public class RuntimeRequestAuditProperties {
    private boolean terminationReceiptEnabled = true;
    private Duration terminationReceiptRetention = Duration.ofDays(7);
    private Duration terminationConvergenceTimeout = Duration.ofMinutes(5);
    private Duration retention = Duration.ofHours(24);
    private Duration maxQueryWindow = Duration.ofMinutes(15);
    private int defaultLimit = 20;
    private int maxLimit = 100;
    private int cleanupBatchSize = 200;
    private int cleanupMaxBatches = 100;
}
