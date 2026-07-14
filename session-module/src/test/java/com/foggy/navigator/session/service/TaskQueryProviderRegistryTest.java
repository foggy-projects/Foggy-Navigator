package com.foggy.navigator.session.service;

import com.foggy.navigator.common.dto.DispatchTaskDTO;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TaskQueryProviderRegistryTest {

    @Test
    void providersSupporting_prefersDeclaredCapabilityProviders() {
        StubProvider legacyProvider = new StubProvider("legacy", Set.of());
        StubProvider listProvider = new StubProvider("list-provider", Set.of(TaskQueryCapability.LIST_TASKS_PAGED));
        StubProvider searchProvider = new StubProvider("search-provider", Set.of(TaskQueryCapability.SEARCH_SESSIONS));
        TaskQueryProviderRegistry registry = registry(List.of(legacyProvider, listProvider, searchProvider));

        List<TaskListingProvider> providers = registry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_PAGED);

        assertEquals(List.of(listProvider), providers);
    }

    @Test
    void providersSupporting_fallsBackToAllProvidersWhenCapabilityIsUndeclared() {
        StubProvider legacyProvider = new StubProvider("legacy", Set.of());
        StubProvider searchProvider = new StubProvider("search-provider", Set.of(TaskQueryCapability.SEARCH_SESSIONS));
        TaskQueryProviderRegistry registry = registry(List.of(legacyProvider, searchProvider));

        List<TaskListingProvider> providers = registry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_PAGED);

        assertEquals(List.of(legacyProvider, searchProvider), providers);
    }

    @Test
    void providersSupporting_returnsAllProvidersForNullCapability() {
        StubProvider firstProvider = new StubProvider("first", Set.of());
        StubProvider secondProvider = new StubProvider("second", Set.of(TaskQueryCapability.SEARCH_SESSIONS));
        TaskQueryProviderRegistry registry = registry(List.of(firstProvider, secondProvider));

        List<TaskListingProvider> providers = registry.listingProvidersSupporting(null);

        assertEquals(2, providers.size());
        assertSame(firstProvider, providers.get(0));
        assertSame(secondProvider, providers.get(1));
    }

    @Test
    void lookupProviders_returnsProvidersAsLookupPorts() {
        StubProvider firstProvider = new StubProvider("first", Set.of());
        StubProvider secondProvider = new StubProvider("second", Set.of());
        TaskQueryProviderRegistry registry = registry(List.of(firstProvider, secondProvider));

        List<TaskLookupProvider> providers = registry.lookupProviders();

        assertEquals(2, providers.size());
        assertSame(firstProvider, providers.get(0));
        assertSame(secondProvider, providers.get(1));
    }

    @Test
    void listingProvidersSupporting_returnsProvidersAsListingPorts() {
        StubProvider legacyProvider = new StubProvider("legacy", Set.of());
        StubProvider listProvider = new StubProvider("list-provider", Set.of(TaskQueryCapability.LIST_TASKS_PAGED));
        TaskQueryProviderRegistry registry = registry(List.of(legacyProvider, listProvider));

        List<TaskListingProvider> providers = registry.listingProvidersSupporting(TaskQueryCapability.LIST_TASKS_PAGED);

        assertEquals(List.of(listProvider), providers);
    }

    @Test
    void workerSessionProvidersSupporting_returnsProvidersAsWorkerSessionPorts() {
        StubProvider legacyProvider = new StubProvider("legacy", Set.of());
        StubProvider workerProvider = new StubProvider("worker-provider", Set.of(TaskQueryCapability.LIST_WORKER_SESSIONS));
        TaskQueryProviderRegistry registry = registry(List.of(legacyProvider, workerProvider));

        List<WorkerSessionQueryProvider> providers =
                registry.workerSessionProvidersSupporting(TaskQueryCapability.LIST_WORKER_SESSIONS);

        assertEquals(List.of(workerProvider), providers);
    }

    @Test
    void findCommandProviderByType_returnsProviderAsCommandPort() {
        StubProvider provider = new StubProvider("provider", Set.of());
        TaskQueryProviderRegistry registry = registry(List.of(provider));

        TaskCommandProvider result = registry.findCommandProviderByType("provider").orElseThrow();

        assertSame(provider, result);
    }

    @Test
    void findCommandProviderForTask_returnsProviderOwningTaskAsCommandPort() {
        StubProvider firstProvider = new StubProvider("first", Set.of());
        StubProvider owningProvider = new StubProvider("owner", Set.of(), Set.of("task-1"));
        TaskQueryProviderRegistry registry = registry(List.of(firstProvider, owningProvider));

        TaskCommandProvider result = registry.findCommandProviderForTask("task-1").orElseThrow();

        assertSame(owningProvider, result);
    }

    @Test
    void findCommandProviderForTask_supportsSeparatedLookupAndCommandPorts() {
        LookupOnlyProvider lookupProvider = new LookupOnlyProvider("split-provider", Set.of("task-1"));
        CommandOnlyProvider commandProvider = new CommandOnlyProvider("split-provider");
        TaskQueryProviderRegistry registry = new TaskQueryProviderRegistry(
                List.of(lookupProvider),
                List.of(commandProvider),
                List.of(),
                List.of());

        TaskCommandProvider result = registry.findCommandProviderForTask("task-1").orElseThrow();

        assertSame(commandProvider, result);
    }

    private static TaskQueryProviderRegistry registry(List<? extends StubProvider> providers) {
        return new TaskQueryProviderRegistry(providers, providers, providers, providers);
    }

    private interface TypedTaskProvider extends TaskLookupProvider,
            TaskCommandProvider,
            TaskListingProvider,
            WorkerSessionQueryProvider {
    }

    private static final class StubProvider implements TypedTaskProvider {

        private final String providerType;
        private final Set<TaskQueryCapability> capabilities;
        private final Set<String> taskIds;

        private StubProvider(String providerType, Set<TaskQueryCapability> capabilities) {
            this(providerType, capabilities, Set.of());
        }

        private StubProvider(String providerType, Set<TaskQueryCapability> capabilities, Set<String> taskIds) {
            this.providerType = providerType;
            this.capabilities = capabilities;
            this.taskIds = taskIds;
        }

        @Override
        public String getProviderType() {
            return providerType;
        }

        @Override
        public Set<TaskQueryCapability> getCapabilities() {
            return capabilities;
        }

        @Override
        public Optional<DispatchTaskDTO> getTaskById(String taskId) {
            if (!taskIds.contains(taskId)) {
                return Optional.empty();
            }
            return Optional.of(DispatchTaskDTO.builder().taskId(taskId).providerType(providerType).build());
        }

        @Override
        public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
            return Optional.empty();
        }

        @Override
        public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
            return List.of();
        }

        @Override
        public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
            return List.of();
        }
    }

    private static final class LookupOnlyProvider implements TaskLookupProvider {

        private final String providerType;
        private final Set<String> taskIds;

        private LookupOnlyProvider(String providerType, Set<String> taskIds) {
            this.providerType = providerType;
            this.taskIds = taskIds;
        }

        @Override
        public String getProviderType() {
            return providerType;
        }

        @Override
        public Optional<DispatchTaskDTO> getTaskById(String taskId) {
            if (!taskIds.contains(taskId)) {
                return Optional.empty();
            }
            return Optional.of(DispatchTaskDTO.builder().taskId(taskId).providerType(providerType).build());
        }

        @Override
        public Optional<DispatchTaskDTO> getTaskByIdAndUser(String taskId, String userId) {
            return Optional.empty();
        }

        @Override
        public List<DispatchTaskDTO> listTasksBySession(String sessionId) {
            return List.of();
        }

        @Override
        public List<DispatchTaskDTO> listActiveDispatchTasks(String userId) {
            return List.of();
        }
    }

    private static final class CommandOnlyProvider implements TaskCommandProvider {

        private final String providerType;

        private CommandOnlyProvider(String providerType) {
            this.providerType = providerType;
        }

        @Override
        public String getProviderType() {
            return providerType;
        }
    }
}
