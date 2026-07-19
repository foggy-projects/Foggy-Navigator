package com.foggy.navigator.sdk.cli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/** Non-policy build provenance; it must never be used to authorize an action. */
final class CliProvenance {
    static final String RESOURCE = "/com/foggy/navigator/sdk/cli/authorization-provenance.properties";

    private final Properties values;

    private CliProvenance(Properties values) {
        this.values = values;
    }

    static CliProvenance load() {
        try (InputStream input = CliProvenance.class.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IllegalStateException("CLI provenance resource is unavailable");
            }
            Properties values = new Properties();
            values.load(input);
            return new CliProvenance(values);
        } catch (IOException exception) {
            throw new IllegalStateException("CLI provenance resource cannot be read", exception);
        }
    }

    String sourceVersion() {
        return required("source.version");
    }

    String publishedVersion() {
        return required("published.version");
    }

    String artifactDrift() {
        return required("artifact.drift");
    }

    String manifestSha256() {
        return required("canonical.manifest.sha256");
    }

    int manifestEntryCount() {
        try {
            return Integer.parseInt(required("canonical.manifest.entry-count"));
        } catch (NumberFormatException exception) {
            throw new IllegalStateException("CLI provenance manifest entry count is invalid", exception);
        }
    }

    private String required(String key) {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("CLI provenance is missing " + key);
        }
        return value.trim();
    }
}
