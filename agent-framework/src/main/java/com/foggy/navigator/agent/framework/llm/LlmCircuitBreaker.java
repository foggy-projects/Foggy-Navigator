package com.foggy.navigator.agent.framework.llm;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * LLM 调用熔断器
 * 状态机：CLOSED → OPEN → HALF_OPEN → CLOSED/OPEN
 *
 * <ul>
 *   <li>CLOSED: 正常放行，连续失败达到阈值后切换为 OPEN</li>
 *   <li>OPEN: 拒绝所有调用，等待冷却时间后切换为 HALF_OPEN</li>
 *   <li>HALF_OPEN: 放行一次探测请求，成功则 CLOSED，失败则 OPEN</li>
 * </ul>
 */
@Slf4j
public class LlmCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicLong openedAt = new AtomicLong(0);

    public LlmCircuitBreaker(int failureThreshold, long cooldownMs) {
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    /**
     * 调用前检查：是否允许通过
     * @return true 允许调用, false 被熔断
     */
    public boolean allowRequest() {
        State current = state.get();

        if (current == State.CLOSED) {
            return true;
        }

        if (current == State.OPEN) {
            if (System.currentTimeMillis() - openedAt.get() >= cooldownMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("LLM circuit breaker: OPEN → HALF_OPEN (cooldown elapsed)");
                }
                return true;
            }
            return false;
        }

        // HALF_OPEN: 只放行一次探测
        return true;
    }

    /**
     * 记录调用成功
     */
    public void recordSuccess() {
        State previous = state.get();
        consecutiveFailures.set(0);
        if (previous != State.CLOSED) {
            state.set(State.CLOSED);
            log.info("LLM circuit breaker: {} → CLOSED (success)", previous);
        }
    }

    /**
     * 记录调用失败
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();

        State current = state.get();
        if (current == State.HALF_OPEN) {
            state.set(State.OPEN);
            openedAt.set(System.currentTimeMillis());
            log.warn("LLM circuit breaker: HALF_OPEN → OPEN (probe failed, failures={})", failures);
            return;
        }

        if (current == State.CLOSED && failures >= failureThreshold) {
            state.set(State.OPEN);
            openedAt.set(System.currentTimeMillis());
            log.warn("LLM circuit breaker: CLOSED → OPEN (consecutive failures={} >= threshold={})",
                    failures, failureThreshold);
        }
    }

    public State getState() {
        return state.get();
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
