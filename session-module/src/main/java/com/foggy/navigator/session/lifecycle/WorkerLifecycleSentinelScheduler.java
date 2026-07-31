package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSnapshotRepository;
import com.foggy.navigator.spi.lifecycle.WorkerLifecyclePortResolver;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Real product scheduler. Only already-enrolled lifecycle Workers are polled;
 * it cannot create a first ENFORCED aggregate or discover unrelated runtimes.
 */
@Service
public class WorkerLifecycleSentinelScheduler {
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
        for (var worker : workers.findAll()) {
            for (var resolver : resolvers) {
                var port = resolver.resolve(worker.getPhysicalWorkerId());
                if (port.isPresent()) {
                    sentinel.reconcile(
                            worker.getPhysicalWorkerId(),
                            SentinelTrigger.TIMER,
                            port.get());
                    break;
                }
            }
        }
    }
}
