package com.foggy.navigator.common.config;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ComponentScan;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CommonAutoConfigurationTest {

    @Test
    void componentScanIncludesMigrationPackage() {
        ComponentScan componentScan = CommonAutoConfiguration.class.getAnnotation(ComponentScan.class);

        assertTrue(Arrays.asList(componentScan.basePackages())
                .contains("com.foggy.navigator.common.migration"));
    }
}
