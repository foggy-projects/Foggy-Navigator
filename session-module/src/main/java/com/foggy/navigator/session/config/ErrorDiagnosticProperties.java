package com.foggy.navigator.session.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "navigator.error-diagnostics")
public class ErrorDiagnosticProperties {
    private int retentionDays = 90;
    private boolean publicSharingEnabled = false;
    private int defaultShareDays = 7;
    private int maxShareDays = 30;
    private boolean includeSafeStack = false;
}
