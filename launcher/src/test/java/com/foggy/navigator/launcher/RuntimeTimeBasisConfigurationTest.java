package com.foggy.navigator.launcher;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeTimeBasisConfigurationTest {

    @Test
    void jdbcTimestampNormalizationIsPinnedToUtc() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertEquals("UTC", properties.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
    }

    @Test
    void terminationReceiptRetentionAndNightlyCleanupDefaultsAreExplicit() {
        YamlPropertiesFactoryBean loader = new YamlPropertiesFactoryBean();
        loader.setResources(new ClassPathResource("application.yml"));
        Properties properties = loader.getObject();

        assertEquals("${NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_ENABLED:true}",
                properties.getProperty("navigator.runtime-audit.termination-receipt-enabled"));
        assertEquals("${NAVIGATOR_RUNTIME_AUDIT_TERMINATION_RECEIPT_RETENTION:P7D}",
                properties.getProperty("navigator.runtime-audit.termination-receipt-retention"));
        assertEquals("${NAVIGATOR_RUNTIME_AUDIT_RETENTION:PT24H}",
                properties.getProperty("navigator.runtime-audit.retention"));
        assertEquals("${NAVIGATOR_RUNTIME_AUDIT_CLEANUP_CRON:0 0 2 * * *}",
                properties.getProperty("navigator.runtime-audit.cleanup-cron"));
    }
}
