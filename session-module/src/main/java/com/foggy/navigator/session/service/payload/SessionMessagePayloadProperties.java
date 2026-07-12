package com.foggy.navigator.session.service.payload;

import java.time.Duration;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Configuration contract for OPT-001's payload backend and bounded preview. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "foggy.session.message-payload")
public class SessionMessagePayloadProperties {

    /** Enables descriptor/file routing. Production keeps rollout control in its profile. */
    private boolean enabled = true;

    /** Existing BUG-021 durable metadata guard; do not lower without a capacity decision. */
    private long inlinePreviewBytes = 48L * 1024L;

    /** Explicit guardrail for one stored object; an over-limit payload becomes UNAVAILABLE. */
    private long maxPayloadBytes = 64L * 1024L * 1024L;

    /** Zero means no automatic expiry; cleanup belongs to a later stage. */
    private Duration retention = Duration.ZERO;

    private Filesystem filesystem = new Filesystem();

    @Getter
    @Setter
    public static class Filesystem {
        /** Must point to persistent storage in an enabled production rollout. */
        private String directory = "./data/session-message-payloads";
    }
}
