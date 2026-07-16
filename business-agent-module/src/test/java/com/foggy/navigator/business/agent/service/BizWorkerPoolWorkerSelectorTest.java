package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolEntity;
import com.foggy.navigator.business.agent.model.entity.BizWorkerPoolMemberEntity;
import com.foggy.navigator.business.agent.repository.BizWorkerPoolMemberRepository;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BizWorkerPoolWorkerSelectorTest {

    private final BizWorkerPoolService poolService = mock(BizWorkerPoolService.class);
    private final BizWorkerPoolMemberRepository memberRepository = mock(BizWorkerPoolMemberRepository.class);
    private final BizWorkerPoolWorkerSelector selector = new BizWorkerPoolWorkerSelector(poolService, memberRepository);

    @Test
    void resolvesRequestedEnabledMember() {
        when(poolService.requireAvailablePool("tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1"))
                .thenReturn(pool());
        when(memberRepository.findByPoolIdOrderByCreatedAtAsc("pool_1"))
                .thenReturn(List.of(member("worker_1", BizWorkerPoolService.STATUS_ENABLED)));

        assertEquals("worker_1", selector.resolveEnabledWorkerId(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1", "OPENAI_CODEX", "worker_1"));
    }

    @Test
    void rejectsRequestedWorkerThatIsNotAnEnabledMember() {
        when(poolService.requireAvailablePool("tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1"))
                .thenReturn(pool());
        when(memberRepository.findByPoolIdOrderByCreatedAtAsc("pool_1"))
                .thenReturn(List.of(member("worker_disabled", BizWorkerPoolService.STATUS_DISABLED)));

        SecurityException error = assertThrows(SecurityException.class, () -> selector.resolveEnabledWorkerId(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1", "OPENAI_CODEX", "worker_not_in_pool"));

        assertEquals("physical worker is not an enabled pool member: worker_not_in_pool", error.getMessage());
    }

    @Test
    void selectsFirstEnabledMemberWhenWorkerIsNotConstrained() {
        when(poolService.requireAvailablePool("tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1"))
                .thenReturn(pool());
        when(memberRepository.findByPoolIdOrderByCreatedAtAsc("pool_1"))
                .thenReturn(List.of(
                        member("worker_disabled", BizWorkerPoolService.STATUS_DISABLED),
                        member("worker_enabled", BizWorkerPoolService.STATUS_ENABLED)));

        assertEquals("worker_enabled", selector.resolveEnabledWorkerId(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1", "OPENAI_CODEX", null));
    }

    @Test
    void rejectsPoolWithNoEnabledMembersWhenWorkerIsNotConstrained() {
        when(poolService.requireAvailablePool("tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1"))
                .thenReturn(pool());
        when(memberRepository.findByPoolIdOrderByCreatedAtAsc("pool_1"))
                .thenReturn(List.of(member("worker_disabled", BizWorkerPoolService.STATUS_DISABLED)));

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> selector.resolveEnabledWorkerId(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1", "OPENAI_CODEX", null));

        assertEquals("worker pool has no enabled members: pool_1", error.getMessage());
    }

    @Test
    void rejectsPoolWithDifferentWorkerBackend() {
        BizWorkerPoolEntity pool = pool();
        pool.setWorkerBackend("CLAUDE");
        when(poolService.requireAvailablePool("tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1"))
                .thenReturn(pool);

        IllegalStateException error = assertThrows(IllegalStateException.class, () -> selector.resolveEnabledWorkerId(
                "tenant_1", ResourceOwnerType.UPSTREAM_SYSTEM, "usys_1", "pool_1", "OPENAI_CODEX", "worker_1"));

        assertEquals("worker pool backend mismatch: pool_1", error.getMessage());
    }

    private BizWorkerPoolEntity pool() {
        BizWorkerPoolEntity pool = new BizWorkerPoolEntity();
        pool.setPoolId("pool_1");
        pool.setWorkerBackend("OPENAI_CODEX");
        return pool;
    }

    private BizWorkerPoolMemberEntity member(String workerId, String status) {
        BizWorkerPoolMemberEntity member = new BizWorkerPoolMemberEntity();
        member.setWorkerId(workerId);
        member.setStatus(status);
        return member;
    }
}
