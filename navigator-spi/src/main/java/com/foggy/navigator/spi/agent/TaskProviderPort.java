package com.foggy.navigator.spi.agent;

import java.util.Set;

/**
 * Base metadata contract for task provider ports.
 */
public interface TaskProviderPort {

    /** Provider type, aligned with A2aAgentProvider.getProviderType(). */
    String getProviderType();

    /**
     * Optional provider capability metadata.
     * <p>
     * The default is empty for backward compatibility. Aggregators should treat
     * an empty capability set as "legacy provider, probe by existing behavior".
     */
    default Set<TaskQueryCapability> getCapabilities() {
        return Set.of();
    }

    default boolean supports(TaskQueryCapability capability) {
        return capability != null && getCapabilities().contains(capability);
    }
}
