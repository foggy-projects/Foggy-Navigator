package com.foggy.navigator.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildMetadataResourceTest {

    @Test
    void packagedMetadataContainsOnlySafeBuildAndRevisionFields() throws IOException {
        Properties build = load("META-INF/build-info.properties");
        Properties git = load("git.properties");

        assertFalse(build.getProperty("build.version", "").isBlank());
        assertFalse(build.getProperty("build.time", "").isBlank());
        assertFalse(git.getProperty("git.commit.id.full", "").isBlank());
        assertFalse(git.getProperty("git.commit.id.abbrev", "").isBlank());
        assertFalse(git.getProperty("git.commit.time", "").isBlank());
        assertFalse(git.getProperty("git.dirty", "").isBlank());
        assertTrue(git.stringPropertyNames().stream()
                .noneMatch(key -> key.contains("user") || key.contains("email") || key.contains("message")));
    }

    private Properties load(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, () -> "missing generated build metadata resource: " + resource);
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }
}
