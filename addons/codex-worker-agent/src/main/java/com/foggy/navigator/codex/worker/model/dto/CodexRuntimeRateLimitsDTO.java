package com.foggy.navigator.codex.worker.model.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CodexRuntimeRateLimitsDTO {

    @JsonAlias("contract_version")
    private Integer contractVersion;

    @JsonAlias("runtime_id")
    private String runtimeId;

    @JsonAlias("runtime_revision")
    private Integer runtimeRevision;

    @JsonAlias("instance_id")
    private String instanceId;

    private String scope;

    private State state;

    @JsonAlias("observed_at_epoch_ms")
    private Long observedAtEpochMs;

    private Boolean stale;

    private List<Limit> limits;

    @JsonAlias("error_code")
    private String errorCode;

    public enum State {
        AVAILABLE,
        LIMIT_REACHED,
        STALE,
        UNSUPPORTED,
        UNKNOWN
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Limit {

        @JsonAlias("limit_id")
        private String limitId;

        @JsonAlias("limit_name")
        private String limitName;

        private Window primary;

        private Window secondary;

        @JsonAlias("rate_limit_reached_type")
        private String rateLimitReachedType;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Window {

        @JsonAlias("used_percent")
        private Integer usedPercent;

        @JsonAlias("window_duration_mins")
        private Long windowDurationMins;

        /** Provider reset timestamp in epoch seconds. */
        @JsonAlias("resets_at")
        private Long resetsAt;
    }
}
