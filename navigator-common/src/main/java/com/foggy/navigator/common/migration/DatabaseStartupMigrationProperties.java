package com.foggy.navigator.common.migration;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "navigator.database.startup-migrations")
public class DatabaseStartupMigrationProperties {

    /**
     * Keep enabled by default to preserve existing startup migration behavior.
     */
    private boolean enabled = true;

    /**
     * Print the manifest without applying migrations.
     */
    private boolean dryRun = false;
}
