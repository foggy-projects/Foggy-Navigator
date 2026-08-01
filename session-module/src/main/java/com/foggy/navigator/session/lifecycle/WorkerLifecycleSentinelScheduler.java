package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePortResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.TreeSet;

/**
 * Real product scheduler. Only already-enrolled lifecycle Workers are polled;
 * it cannot create a first ENFORCED aggregate or discover unrelated runtimes.
 */
@Service
public class WorkerLifecycleSentinelScheduler {
    private static final Logger log =
            LoggerFactory.getLogger(WorkerLifecycleSentinelScheduler.class);
    private final WorkerLifecycleSnapshotRepository workers;
    private final WorkerLifecycleSentinelService sentinel;
    private final List<WorkerLifecyclePortResolver> resolvers;

    public WorkerLifecycleSentinelScheduler(
            WorkerLifecycleSnapshotRepository workers,
            WorkerLifecycleSentinelService sentinel,
            List<WorkerLifecyclePortResolver> resolvers) {
        this.workers = workers;
        this.sentinel = sentinel;
        this.resolvers = List.copyOf(resolvers);
    }

    @Scheduled(fixedDelayString =
            "${navigator.lifecycle.sentinel.fixed-delay-ms:15000}")
    public void reconcileEnrolledWorkers() {
        TreeSet<String> candidates = new TreeSet<>();
        workers.findAll().forEach(
                worker -> candidates.add(worker.getPhysicalWorkerId()));
        resolvers.forEach(
                resolver -> candidates.addAll(resolver.discoverShadowWorkers()));
        for (String physicalWorkerId : candidates) {
            for (var resolver : resolvers) {
                var port = resolver.resolve(physicalWorkerId);
                if (port.isPresent()) {
                    try {
                        sentinel.reconcile(
                                physicalWorkerId, SentinelTrigger.TIMER, port.get());
                    } catch (RuntimeException isolatedWorkerFailure) {
                        // One unavailable/malformed runtime must not prevent the
                        // scheduled scan from reconciling other configured Workers.
                        log.warn(
                                "Lifecycle Sentinel reconciliation failed: workerId={}, error={}",
                                physicalWorkerId,
                                safeError(isolatedWorkerFailure));
                    }
                    break;
                }
            }
        }
    }

    private String safeError(RuntimeException failure) {
        String message = failure.getMessage();
        return message != null && message.matches("[A-Z0-9_]{1,96}")
                ? message
                : failure.getClass().getSimpleName();
    }
}
