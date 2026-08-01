package com.foggy.navigator.session.controller;

import com.foggy.navigator.session.lifecycle.LifecycleActivationAuthorityService;
import com.foggy.navigator.session.lifecycle.LifecycleActivationControlAuthorizer;
import com.foggy.navigator.session.lifecycle.LifecycleActivationDeniedException;
import com.foggyframework.core.ex.RX;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/lifecycle-activation/v1/targets/{targetId}")
public class LifecycleActivationController {
    static final String CONTROL_HEADER = "X-Navigator-Activation-Control";

    private final LifecycleActivationControlAuthorizer authorizer;
    private final LifecycleActivationAuthorityService authority;

    public LifecycleActivationController(
            LifecycleActivationControlAuthorizer authorizer,
            LifecycleActivationAuthorityService authority) {
        this.authorizer = authorizer;
        this.authority = authority;
    }

    @GetMapping("/readiness")
    public RX<LifecycleActivationAuthorityService.ActivationReadiness> readiness(
            @PathVariable String targetId,
            @RequestHeader(value = CONTROL_HEADER, required = false)
            String controlToken) {
        return invoke(targetId, controlToken, authority::inspect);
    }

    @PostMapping("/registration")
    public RX<LifecycleActivationAuthorityService.ActivationReadiness> register(
            @PathVariable String targetId,
            @RequestHeader(value = CONTROL_HEADER, required = false)
            String controlToken) {
        return invoke(targetId, controlToken,
                () -> authority.registerConfiguredTarget(targetId));
    }

    @PostMapping("/proof:acquire")
    public RX<LifecycleActivationAuthorityService.ActivationReadiness> acquire(
            @PathVariable String targetId,
            @RequestHeader(value = CONTROL_HEADER, required = false)
            String controlToken) {
        return invoke(targetId, controlToken,
                () -> authority.acquireConfiguredProof(targetId));
    }

    @PostMapping("/proof:renew")
    public RX<LifecycleActivationAuthorityService.ActivationReadiness> renew(
            @PathVariable String targetId,
            @RequestHeader(value = CONTROL_HEADER, required = false)
            String controlToken) {
        return invoke(targetId, controlToken,
                authority::observeAndRenewConfiguredProof);
    }

    @PostMapping("/proof:quarantine")
    public RX<LifecycleActivationAuthorityService.ActivationReadiness> quarantine(
            @PathVariable String targetId,
            @RequestHeader(value = CONTROL_HEADER, required = false)
            String controlToken) {
        return invoke(targetId, controlToken, () -> {
            authority.quarantineConfiguredTarget(
                    "LIFECYCLE_ACTIVATION_OPERATOR_STOP");
            return authority.inspect();
        });
    }

    private RX<LifecycleActivationAuthorityService.ActivationReadiness> invoke(
            String targetId,
            String controlToken,
            Operation operation) {
        try {
            authorizer.requireAuthorized(targetId, controlToken);
            return RX.ok(operation.run());
        } catch (LifecycleActivationDeniedException denied) {
            return RX.failB(denied.getMessage());
        } catch (RuntimeException unavailable) {
            return RX.failB("LIFECYCLE_ACTIVATION_CONTROL_FAILED");
        }
    }

    @FunctionalInterface
    private interface Operation {
        LifecycleActivationAuthorityService.ActivationReadiness run();
    }
}
