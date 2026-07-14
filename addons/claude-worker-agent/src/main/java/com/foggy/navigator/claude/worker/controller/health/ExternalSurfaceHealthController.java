package com.foggy.navigator.claude.worker.controller.health;

import com.foggy.navigator.claude.worker.config.ExternalSurfaceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Non-sensitive routing readiness for the public Open API surface.
 */
@RestController
@RequestMapping("/api/v1/health/external-surface")
@RequiredArgsConstructor
public class ExternalSurfaceHealthController {

    private final ExternalSurfaceProperties properties;

    @GetMapping
    public ExternalSurfaceStatus status() {
        boolean enabled = properties.isEnabled();
        return new ExternalSurfaceStatus(
                enabled,
                enabled ? "external-enabled" : "internal-only",
                enabled,
                "platform-routing-only",
                false,
                false
        );
    }

    /**
     * surfaceReady only describes this HTTP route gate. Provider and production
     * readiness require separate checks and are intentionally never inferred here.
     */
    public record ExternalSurfaceStatus(
            boolean enabled,
            String mode,
            boolean surfaceReady,
            String readinessScope,
            boolean providerReadinessAssessed,
            boolean productionReady
    ) {
    }
}
