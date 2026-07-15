package com.foggy.navigator.business.agent.repository;

import com.foggy.navigator.business.agent.TestApplication;
import com.foggy.navigator.business.agent.model.dto.BizWorkerCredentialDTO;
import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.business.agent.service.BizWorkerCredentialService;
import com.foggy.navigator.business.agent.service.BizWorkerPoolService;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@ActiveProfiles("test")
@ContextConfiguration(classes = TestApplication.class)
@Import(BizWorkerCredentialService.class)
class BizWorkerCredentialLifecycleJpaTest {

    private final BizWorkerIdentityRepository repository;
    private final BizWorkerCredentialService service;

    @Autowired
    BizWorkerCredentialLifecycleJpaTest(
            BizWorkerIdentityRepository repository,
            BizWorkerCredentialService service) {
        this.repository = repository;
        this.service = service;
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rotationPersistsOnlyHashAndRevocationFailsClosed() {
        String workerId = createLegacyIdentity();

        BizWorkerCredentialDTO issued = service.rotatePlatformCredential(workerId, 600L);

        BizWorkerIdentityEntity persisted = repository.findByWorkerId(workerId).orElseThrow();
        assertThat(issued.getSecret()).startsWith("bwc_");
        assertThat(persisted.getTokenHash()).isNotEqualTo(issued.getSecret());
        assertThat(persisted.getCredentialVersion()).isEqualTo(1);
        assertThat(persisted.getCredentialIssuedAt()).isNotNull();
        assertThat(persisted.getCredentialExpiresAt()).isAfter(persisted.getCredentialIssuedAt());
        assertThat(persisted.getCredentialRotatedAt()).isNotNull();
        assertThat(persisted.getRowVersion()).isPositive();
        assertThat(service.requireStrictCredential(workerId, issued.getSecret()).getWorkerId())
                .isEqualTo(workerId);

        BizWorkerCredentialDTO revoked = service.revokePlatformCredential(workerId);

        assertThat(revoked.getSecret()).isNull();
        assertThat(repository.findByWorkerId(workerId).orElseThrow().getCredentialRevokedAt()).isNotNull();
        assertThatThrownBy(() -> service.requireStrictCredential(workerId, issued.getSecret()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("invalid worker credential");

        BizWorkerCredentialDTO rotated = service.rotatePlatformCredential(workerId, 600L);

        assertThat(rotated.getCredentialVersion()).isEqualTo(2);
        assertThatThrownBy(() -> service.requireStrictCredential(workerId, issued.getSecret()))
                .isInstanceOf(SecurityException.class)
                .hasMessage("invalid worker credential");
        assertThat(service.requireStrictCredential(workerId, rotated.getSecret()).getCredentialVersion())
                .isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentRotationsSerializeThroughPessimisticLockAndVersionColumn() throws Exception {
        String workerId = createLegacyIdentity();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<BizWorkerCredentialDTO> first = executor.submit(
                    () -> rotateAfterBarrier(workerId, ready, start));
            Future<BizWorkerCredentialDTO> second = executor.submit(
                    () -> rotateAfterBarrier(workerId, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<BizWorkerCredentialDTO> results = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS));

            assertThat(results).extracting(BizWorkerCredentialDTO::getCredentialVersion)
                    .containsExactlyInAnyOrder(1, 2);
            BizWorkerIdentityEntity persisted = repository.findByWorkerId(workerId).orElseThrow();
            assertThat(persisted.getCredentialVersion()).isEqualTo(2);
            assertThat(persisted.getRowVersion()).isGreaterThanOrEqualTo(2L);

            long accepted = results.stream()
                    .filter(result -> accepts(workerId, result.getSecret()))
                    .count();
            assertThat(accepted).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private BizWorkerCredentialDTO rotateAfterBarrier(
            String workerId, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        return service.rotatePlatformCredential(workerId, 600L);
    }

    private boolean accepts(String workerId, String secret) {
        try {
            service.requireStrictCredential(workerId, secret);
            return true;
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private String createLegacyIdentity() {
        String workerId = "worker-jpa-" + UUID.randomUUID();
        BizWorkerIdentityEntity identity = new BizWorkerIdentityEntity();
        identity.setWorkerId(workerId);
        identity.setOwnerType(ResourceOwnerType.PLATFORM);
        identity.setOwnerId(BizWorkerPoolService.PLATFORM_OWNER_ID);
        identity.setWorkerBackend("LANGGRAPH_BIZ");
        identity.setBaseUrl("http://127.0.0.1:3061");
        identity.setStatus(BizWorkerPoolService.STATUS_ENABLED);
        identity.setHealthStatus(BizWorkerPoolService.HEALTHY);
        identity.setCredentialVersion(0);
        identity.setTokenHash("legacy-hash");
        repository.saveAndFlush(identity);
        return workerId;
    }
}
