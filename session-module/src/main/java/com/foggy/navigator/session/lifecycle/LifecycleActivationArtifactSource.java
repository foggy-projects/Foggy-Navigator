package com.foggy.navigator.session.lifecycle;

public interface LifecycleActivationArtifactSource {
    ActivationArtifacts load();

    record ActivationArtifacts(
            LifecycleActivationManifest manifest,
            String manifestDigest,
            LifecycleActivationManifest.ControllerObservation observation) {
    }
}
