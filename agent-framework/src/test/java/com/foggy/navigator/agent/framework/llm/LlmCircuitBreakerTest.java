package com.foggy.navigator.agent.framework.llm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LlmCircuitBreaker 测试")
class LlmCircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long COOLDOWN_MS = 200; // short for testing

    private LlmCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        breaker = new LlmCircuitBreaker(FAILURE_THRESHOLD, COOLDOWN_MS);
    }

    @Nested
    @DisplayName("初始状态")
    class InitialStateTest {

        @Test
        @DisplayName("初始状态应为 CLOSED")
        void shouldStartClosed() {
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("初始应允许请求")
        void shouldAllowRequests() {
            assertTrue(breaker.allowRequest());
        }

        @Test
        @DisplayName("初始连续失败计数应为 0")
        void shouldHaveZeroFailures() {
            assertEquals(0, breaker.getConsecutiveFailures());
        }
    }

    @Nested
    @DisplayName("CLOSED → OPEN 转换")
    class ClosedToOpenTest {

        @Test
        @DisplayName("连续失败达到阈值时应切换到 OPEN")
        void shouldOpenAfterThresholdFailures() {
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                assertTrue(breaker.allowRequest());
                breaker.recordFailure();
            }

            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
        }

        @Test
        @DisplayName("未达阈值时应保持 CLOSED")
        void shouldStayClosed_whenBelowThreshold() {
            for (int i = 0; i < FAILURE_THRESHOLD - 1; i++) {
                breaker.recordFailure();
            }

            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
            assertTrue(breaker.allowRequest());
        }

        @Test
        @DisplayName("成功调用应重置失败计数")
        void shouldResetFailuresOnSuccess() {
            breaker.recordFailure();
            breaker.recordFailure();
            breaker.recordSuccess();

            assertEquals(0, breaker.getConsecutiveFailures());
            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
        }

        @Test
        @DisplayName("OPEN 状态应拒绝请求（冷却期内）")
        void shouldRejectRequests_whenOpen() {
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }

            assertFalse(breaker.allowRequest());
        }
    }

    @Nested
    @DisplayName("OPEN → HALF_OPEN 转换")
    class OpenToHalfOpenTest {

        @Test
        @DisplayName("冷却时间后应切换到 HALF_OPEN 并允许探测")
        void shouldTransitionToHalfOpen_afterCooldown() throws InterruptedException {
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());

            // Wait for cooldown
            Thread.sleep(COOLDOWN_MS + 50);

            assertTrue(breaker.allowRequest());
            assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());
        }
    }

    @Nested
    @DisplayName("HALF_OPEN → CLOSED 转换")
    class HalfOpenToClosedTest {

        @Test
        @DisplayName("探测成功应切换到 CLOSED")
        void shouldCloseOnProbeSuccess() throws InterruptedException {
            // Open the breaker
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }

            // Wait for cooldown
            Thread.sleep(COOLDOWN_MS + 50);

            // Probe request
            assertTrue(breaker.allowRequest());
            assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());

            // Probe succeeds
            breaker.recordSuccess();

            assertEquals(LlmCircuitBreaker.State.CLOSED, breaker.getState());
            assertEquals(0, breaker.getConsecutiveFailures());
            assertTrue(breaker.allowRequest());
        }
    }

    @Nested
    @DisplayName("HALF_OPEN → OPEN 转换")
    class HalfOpenToOpenTest {

        @Test
        @DisplayName("探测失败应切换回 OPEN")
        void shouldReopenOnProbeFailure() throws InterruptedException {
            // Open the breaker
            for (int i = 0; i < FAILURE_THRESHOLD; i++) {
                breaker.recordFailure();
            }

            // Wait for cooldown
            Thread.sleep(COOLDOWN_MS + 50);

            // Transition to HALF_OPEN
            assertTrue(breaker.allowRequest());
            assertEquals(LlmCircuitBreaker.State.HALF_OPEN, breaker.getState());

            // Probe fails
            breaker.recordFailure();

            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
            assertFalse(breaker.allowRequest()); // Should be back in cooldown
        }
    }

    @Nested
    @DisplayName("并发安全性")
    class ConcurrencyTest {

        @Test
        @DisplayName("并发调用 recordFailure 不应导致状态异常")
        void shouldHandleConcurrentFailures() throws InterruptedException {
            int threadCount = 20;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        breaker.recordFailure();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            // After many concurrent failures, state should be OPEN
            assertEquals(LlmCircuitBreaker.State.OPEN, breaker.getState());
            assertEquals(threadCount, breaker.getConsecutiveFailures());
        }

        @Test
        @DisplayName("并发混合成功/失败不应抛出异常")
        void shouldHandleConcurrentMixedCalls() throws InterruptedException {
            int threadCount = 50;
            CountDownLatch latch = new CountDownLatch(threadCount);
            ExecutorService executor = Executors.newFixedThreadPool(10);
            AtomicInteger errors = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                final int idx = i;
                executor.submit(() -> {
                    try {
                        breaker.allowRequest();
                        if (idx % 3 == 0) {
                            breaker.recordSuccess();
                        } else {
                            breaker.recordFailure();
                        }
                    } catch (Exception e) {
                        errors.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await();
            executor.shutdown();

            assertEquals(0, errors.get(), "No exceptions should occur during concurrent operations");
            // State should be one of the valid states
            LlmCircuitBreaker.State state = breaker.getState();
            assertTrue(
                    state == LlmCircuitBreaker.State.CLOSED ||
                    state == LlmCircuitBreaker.State.OPEN ||
                    state == LlmCircuitBreaker.State.HALF_OPEN
            );
        }
    }
}
