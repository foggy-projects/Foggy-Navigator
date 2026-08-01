package com.foggy.navigator.session.lifecycle;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class LifecycleActivationControlAuthorizer {
    private final LifecycleActivationProperties properties;

    public LifecycleActivationControlAuthorizer(
            LifecycleActivationProperties properties) {
        this.properties = properties;
    }

    public void requireAuthorized(String targetId, String presentedToken) {
        if (!properties.isControlEnabled()) {
            throw new LifecycleActivationDeniedException(
                    LifecycleActivationReason.CONTROL_DISABLED);
        }
        String expectedTarget = properties.getExactTargetId();
        String expectedToken = properties.getControlToken();
        if (expectedTarget == null || !expectedTarget.equals(targetId)) {
            throw new LifecycleActivationDeniedException(
                    LifecycleActivationReason.TARGET_MISMATCH);
        }
        if (expectedToken == null || expectedToken.length() < 32
                || presentedToken == null
                || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                presentedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new LifecycleActivationDeniedException(
                    LifecycleActivationReason.CONTROL_UNAUTHORIZED);
        }
    }
}
