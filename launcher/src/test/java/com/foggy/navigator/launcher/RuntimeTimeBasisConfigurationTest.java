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
}
