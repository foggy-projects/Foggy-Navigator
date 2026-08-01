package com.foggy.navigator.session.lifecycle;

import java.time.Instant;
import java.util.List;

public record LifecycleActivationManifest(
        String schema,
        String targetId,
        String runId,
        String targetClass,
        String providerEvidenceLane,
        Candidate candidate,
        ExactTuple exactTuple,
        Target target,
        Worker worker,
        List<Controller> controllers,
        String controllerInventoryDigest) {

    public LifecycleActivationManifest {
        controllers = controllers == null ? List.of() : List.copyOf(controllers);
    }

    public record Candidate(
            String head, String patchSha256, int ownerProtocol) {
    }

    public record ExactTuple(
            String providerType,
            String tenantId,
            String userId,
            String physicalWorkerId,
            String modelConfigId,
            String model,
            String codexHomeKey,
            String promptSha256) {
    }

    public record Target(
            String host,
            int navigatorPort,
            int workerPort,
            int mysqlPort,
            String mysqlVersion,
            String database,
            String dockerProject,
            String root,
            String workdir,
            String workerHome,
            String providerProfile,
            String workerProfile,
            String navigatorRuntimeProfile,
            String databaseProfile,
            String controlProfile,
            String composeFile,
            String evidenceDir,
            String navigatorPidFile,
            String workerPidFile,
            String observationFile) {
    }

    public record Worker(
            String version,
            int protocolVersion,
            List<String> requiredCapabilities) {
        public Worker {
            requiredCapabilities = requiredCapabilities == null
                    ? List.of() : List.copyOf(requiredCapabilities);
        }
    }

    public record Controller(
            String kind,
            String id,
            String state,
            String restartPolicy,
            String ownershipRunId,
            String source,
            String artifactCommit,
            String cwd) {
    }

    public record ControllerObservation(
            String schema,
            String targetId,
            String runId,
            String controllerInventoryDigest,
            String manifestDigest,
            Instant observedAt,
            boolean allKnownControllersDisabled,
            boolean lateRelaunchDetected,
            int unknownControllerCount,
            String evidenceSource) {
    }
}
