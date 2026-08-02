package com.foggy.navigator.session.lifecycle;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("navigator.lifecycle.activation")
public class LifecycleActivationProperties {
    private boolean admissionEnabled;
    private boolean controlEnabled;
    private boolean localDevelopmentTargetEnabled;
    private String exactTargetId;
    private String manifestPath;
    private String observationPath;
    private String instanceId;
    private String candidateHead;
    private String candidatePatchSha256;
    private int ownerProtocol;
    private String controlToken;
    private Duration proofLease = Duration.ofSeconds(30);
    private Duration instanceTtl = Duration.ofSeconds(30);
    private Duration observationMaxAge = Duration.ofSeconds(15);

    public boolean isAdmissionEnabled() { return admissionEnabled; }
    public void setAdmissionEnabled(boolean value) { admissionEnabled = value; }
    public boolean isControlEnabled() { return controlEnabled; }
    public void setControlEnabled(boolean value) { controlEnabled = value; }
    public boolean isLocalDevelopmentTargetEnabled() {
        return localDevelopmentTargetEnabled;
    }
    public void setLocalDevelopmentTargetEnabled(boolean value) {
        localDevelopmentTargetEnabled = value;
    }
    public String getExactTargetId() { return exactTargetId; }
    public void setExactTargetId(String value) { exactTargetId = trim(value); }
    public String getManifestPath() { return manifestPath; }
    public void setManifestPath(String value) { manifestPath = trim(value); }
    public String getObservationPath() { return observationPath; }
    public void setObservationPath(String value) { observationPath = trim(value); }
    public String getInstanceId() { return instanceId; }
    public void setInstanceId(String value) { instanceId = trim(value); }
    public String getCandidateHead() { return candidateHead; }
    public void setCandidateHead(String value) { candidateHead = trim(value); }
    public String getCandidatePatchSha256() { return candidatePatchSha256; }
    public void setCandidatePatchSha256(String value) {
        candidatePatchSha256 = trim(value);
    }
    public int getOwnerProtocol() { return ownerProtocol; }
    public void setOwnerProtocol(int value) { ownerProtocol = value; }
    public String getControlToken() { return controlToken; }
    public void setControlToken(String value) { controlToken = trim(value); }
    public Duration getProofLease() { return proofLease; }
    public void setProofLease(Duration value) { proofLease = value; }
    public Duration getInstanceTtl() { return instanceTtl; }
    public void setInstanceTtl(Duration value) { instanceTtl = value; }
    public Duration getObservationMaxAge() { return observationMaxAge; }
    public void setObservationMaxAge(Duration value) {
        observationMaxAge = value;
    }

    private String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
