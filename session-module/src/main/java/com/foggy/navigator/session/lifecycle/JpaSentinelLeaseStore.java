package com.foggy.navigator.session.lifecycle;

import com.foggy.navigator.session.lifecycle.persistence.WorkerLifecycleSentinelLeaseEntity;
import com.foggy.navigator.session.lifecycle.repository.WorkerLifecycleSentinelLeaseRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Service
public class JpaSentinelLeaseStore implements SentinelLeaseStore {
    private final WorkerLifecycleSentinelLeaseRepository leases;

    public JpaSentinelLeaseStore(WorkerLifecycleSentinelLeaseRepository leases) {
        this.leases = leases;
    }

    @Override
    @Transactional
    public Optional<SentinelLease> tryAcquire(
            String worker, String holder, Instant now, Duration duration) {
        LocalDateTime localNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        WorkerLifecycleSentinelLeaseEntity lease = leases.findForUpdate(worker).orElse(null);
        if (lease != null
                && lease.getExpiresAt().isAfter(localNow)
                && !holder.equals(lease.getHolderInstanceId())) {
            return Optional.empty();
        }
        if (lease == null) {
            lease = new WorkerLifecycleSentinelLeaseEntity();
            lease.setPhysicalWorkerId(worker);
            lease.setFenceToken(1);
        } else {
            lease.setFenceToken(lease.getFenceToken() + 1);
        }
        lease.setHolderInstanceId(holder);
        lease.setExpiresAt(localNow.plus(duration));
        leases.save(lease);
        return Optional.of(new SentinelLease(worker, holder, lease.getFenceToken()));
    }
}
