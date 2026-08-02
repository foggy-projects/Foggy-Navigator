package com.foggy.navigator.claude.worker.service;

/**
 * Stable fail-closed signal for the retired CrossProjectTask mutation surface.
 */
public class CrossProjectMutationRetiredException extends RuntimeException {

    public static final String REASON_CODE = "CROSS_PROJECT_TASK_MUTATION_RETIRED";

    public CrossProjectMutationRetiredException() {
        super(REASON_CODE);
    }
}
