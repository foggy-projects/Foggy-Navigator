package com.foggy.navigator.spi.agent;

/**
 * Backward-compatible aggregate SPI for provider task integration.
 * <p>
 * New session-side code should depend on the narrow ports where possible:
 * {@link TaskLookupProvider}, {@link TaskCommandProvider},
 * {@link TaskListingProvider}, and {@link WorkerSessionQueryProvider}.
 */
public interface TaskQueryProvider extends TaskLookupProvider,
        TaskCommandProvider,
        TaskListingProvider,
        WorkerSessionQueryProvider {
}
