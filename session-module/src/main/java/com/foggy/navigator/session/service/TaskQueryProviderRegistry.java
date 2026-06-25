package com.foggy.navigator.session.service;

import com.foggy.navigator.spi.agent.TaskQueryCapability;
import com.foggy.navigator.spi.agent.TaskCommandProvider;
import com.foggy.navigator.spi.agent.TaskListingProvider;
import com.foggy.navigator.spi.agent.TaskLookupProvider;
import com.foggy.navigator.spi.agent.TaskProviderPort;
import com.foggy.navigator.spi.agent.WorkerSessionQueryProvider;

import java.util.List;
import java.util.Optional;

final class TaskQueryProviderRegistry {

    private final List<TaskLookupProvider> lookupProviders;
    private final List<TaskCommandProvider> commandProviders;
    private final List<TaskListingProvider> listingProviders;
    private final List<WorkerSessionQueryProvider> workerSessionProviders;

    TaskQueryProviderRegistry(List<? extends TaskLookupProvider> lookupProviders,
                              List<? extends TaskCommandProvider> commandProviders,
                              List<? extends TaskListingProvider> listingProviders,
                              List<? extends WorkerSessionQueryProvider> workerSessionProviders) {
        this.lookupProviders = copy(lookupProviders);
        this.commandProviders = copy(commandProviders);
        this.listingProviders = copy(listingProviders);
        this.workerSessionProviders = copy(workerSessionProviders);
    }

    List<TaskLookupProvider> lookupProviders() {
        return lookupProviders;
    }

    List<TaskListingProvider> listingProvidersSupporting(TaskQueryCapability capability) {
        return providersSupporting(listingProviders, capability);
    }

    List<WorkerSessionQueryProvider> workerSessionProvidersSupporting(TaskQueryCapability capability) {
        return providersSupporting(workerSessionProviders, capability);
    }

    Optional<TaskLookupProvider> findLookupProviderByType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return Optional.empty();
        }
        return lookupProviders.stream()
                .filter(provider -> providerType.equals(provider.getProviderType()))
                .findFirst();
    }

    Optional<TaskCommandProvider> findCommandProviderByType(String providerType) {
        if (providerType == null || providerType.isBlank()) {
            return Optional.empty();
        }
        return commandProviders.stream()
                .filter(provider -> providerType.equals(provider.getProviderType()))
                .findFirst();
    }

    Optional<TaskLookupProvider> findLookupProviderForTask(String taskId) {
        for (TaskLookupProvider provider : lookupProviders) {
            if (provider.getTaskById(taskId).isPresent()) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    Optional<TaskCommandProvider> findCommandProviderForTask(String taskId) {
        return findLookupProviderForTask(taskId)
                .flatMap(provider -> findCommandProviderByType(provider.getProviderType()));
    }

    private static <T> List<T> copy(List<? extends T> providers) {
        return providers != null ? List.copyOf(providers) : List.of();
    }

    private static <T extends TaskProviderPort> List<T> providersSupporting(
            List<T> providers,
            TaskQueryCapability capability) {
        if (capability == null) {
            return providers;
        }
        List<T> supportedProviders = providers.stream()
                .filter(provider -> provider.supports(capability))
                .toList();
        return supportedProviders.isEmpty() ? providers : supportedProviders;
    }
}
