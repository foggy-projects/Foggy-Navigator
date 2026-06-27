package com.foggy.navigator.session.service;

import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class TaskProviderLegacyContractTest {

    @Test
    void taskListingLegacyMethodsAreDeprecatedButNotMarkedForRemoval() throws NoSuchMethodException {
        assertDeprecatedSince131(
                TaskListingProvider.class.getMethod("listTasksPaged", String.class, int.class, int.class, String.class));
        assertDeprecatedSince131(
                TaskListingProvider.class.getMethod("searchSessions", String.class, String.class, String.class,
                        String.class, int.class, int.class));
        assertDeprecatedSince131(
                TaskListingProvider.class.getMethod("listTasksByDirectoryPaged", String.class, String.class,
                        int.class, int.class, String.class));
    }

    @Test
    void workerSessionLegacyMethodsAreDeprecatedButNotMarkedForRemoval() throws NoSuchMethodException {
        assertDeprecatedSince131(
                WorkerSessionQueryProvider.class.getMethod("listWorkerSessions", String.class, String.class));
        assertDeprecatedSince131(
                WorkerSessionQueryProvider.class.getMethod("getWorkerSessionMessageCount", String.class,
                        String.class, String.class));
        assertDeprecatedSince131(
                WorkerSessionQueryProvider.class.getMethod("getWorkerSessionMessages", String.class, String.class,
                        String.class, Integer.class, Integer.class));
        assertDeprecatedSince131(
                WorkerSessionQueryProvider.class.getMethod("syncWorkerSessions", String.class, String.class,
                        String.class));
    }

    @Test
    void taskCommandDirectCancelMethodIsNotDeprecated() throws NoSuchMethodException {
        Method method = TaskCommandProvider.class.getMethod("cancelTaskDirect", String.class, String.class);
        assertNull(method.getAnnotation(Deprecated.class), "cancelTaskDirect should be the non-deprecated route");
    }

    @Test
    void taskCommandLegacyCancelMethodIsDeprecatedButNotMarkedForRemoval() throws NoSuchMethodException {
        Method method = TaskCommandProvider.class.getMethod("cancelTask", String.class, String.class);
        Deprecated deprecated = method.getAnnotation(Deprecated.class);
        assertNotNull(deprecated, method.getName() + " should be deprecated");
        assertEquals("1.0.0-SNAPSHOT", deprecated.since());
        assertFalse(deprecated.forRemoval(), method.getName() + " should remain compatibility-only for now");
    }

    private static void assertDeprecatedSince131(Method method) {
        Deprecated deprecated = method.getAnnotation(Deprecated.class);
        assertNotNull(deprecated, method.getName() + " should be deprecated");
        assertEquals("1.3.1", deprecated.since());
        assertFalse(deprecated.forRemoval(), method.getName() + " should remain compatibility-only for now");
    }
}
