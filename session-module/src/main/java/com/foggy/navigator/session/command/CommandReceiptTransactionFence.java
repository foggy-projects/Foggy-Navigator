package com.foggy.navigator.session.command;

import com.foggy.navigator.spi.command.CanonicalCommandEnvelope;

import java.util.Objects;

/**
 * Transaction-local domain participant for a canonical once receipt.
 *
 * <p>A fence may lock and inspect durable domain facts, but it must not invoke a Provider or
 * mutate the command target. The returned guard defers rejection until the receipt service has
 * re-read the receipt state, so a concurrently recorded result remains a replay-only outcome.</p>
 */
public interface CommandReceiptTransactionFence {

    String OPEN_API_AGENT_TASK_CANCEL_ROUTE =
            "/api/v1/open/agents/{agentId}/tasks/{taskId}/cancel";
    String OPEN_API_CLIENT_SURFACE = "NAVIGATOR_OPEN_API";
    String TASK_TERMINATE_ACTION = "task.terminate";

    /** Returns true when this fence owns the route-level command domain. */
    boolean claims(CanonicalCommandEnvelope.CommandBinding binding);

    /** Locks and inspects the claimed domain inside the caller's write transaction. */
    LockedDomain lock(CanonicalCommandEnvelope.CommandBinding binding);

    /**
     * Route-level selector whose commands must never proceed without exactly one claiming fence.
     * Actor and credential checks deliberately belong to the claiming fence so a ClientApp actor
     * cannot fall through the management receipt lane.
     */
    static boolean requiresOpenApiAgentTaskTerminationFence(
            CanonicalCommandEnvelope.CommandBinding binding) {
        if (binding == null) {
            return false;
        }
        return binding.commandKind() == CanonicalCommandEnvelope.CommandKind.TERMINATE
                && binding.ingress().ingress()
                == CanonicalCommandEnvelope.CommandIngress.OPENAPI
                && OPEN_API_CLIENT_SURFACE.equals(binding.ingress().clientSurface())
                && OPEN_API_AGENT_TASK_CANCEL_ROUTE.equals(binding.ingress().routeId());
    }

    /** Content-free result whose failure is raised only if the receipt still needs an effect. */
    record LockedDomain(boolean eligible, String rejectionCode) {

        public LockedDomain {
            if (eligible) {
                if (rejectionCode != null) {
                    throw new IllegalArgumentException(
                            "eligible domain must not carry a rejection code");
                }
            } else if (rejectionCode == null || rejectionCode.isBlank()) {
                throw new IllegalArgumentException(
                        "ineligible domain requires a rejection code");
            }
        }

        public static LockedDomain allowed() {
            return new LockedDomain(true, null);
        }

        public static LockedDomain rejected(String safeCode) {
            return new LockedDomain(false,
                    Objects.requireNonNull(safeCode, "safeCode must not be null"));
        }

        public void requireEligible() {
            if (!eligible) {
                throw new IllegalStateException(rejectionCode);
            }
        }

        @Override
        public String toString() {
            return "LockedDomain[content-free]";
        }
    }
}
