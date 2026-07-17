package com.foggy.navigator.spi.agent;

import java.util.Map;

/**
 * JVM-local markers for trusted task-dispatch paths.
 *
 * <p>The marker value is compared by identity, so a JSON or metadata value with
 * the same key cannot opt into an internal dispatch capability.</p>
 */
public final class InternalTaskDispatchMarkers {

    private static final String INITIALIZE_RUNTIME_AFFINITY_KEY =
            InternalTaskDispatchMarkers.class.getName() + ".initializeRuntimeAffinity";
    private static final Object INITIALIZE_RUNTIME_AFFINITY_TOKEN = new Object();

    private InternalTaskDispatchMarkers() {
    }

    public static void markRuntimeAffinityInitialization(Map<String, Object> params) {
        params.put(INITIALIZE_RUNTIME_AFFINITY_KEY, INITIALIZE_RUNTIME_AFFINITY_TOKEN);
    }

    public static boolean requestsRuntimeAffinityInitialization(Map<String, Object> params) {
        return params != null
                && params.get(INITIALIZE_RUNTIME_AFFINITY_KEY) == INITIALIZE_RUNTIME_AFFINITY_TOKEN;
    }
}
