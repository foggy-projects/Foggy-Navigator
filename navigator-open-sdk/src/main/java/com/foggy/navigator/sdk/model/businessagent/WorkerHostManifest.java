package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedHashMap;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkerHostManifest {
    private String workerHostId;
    private String hostUrl;
    private Integer port;
    private String install;
    private Map<String, WorkerSpec> workers = new LinkedHashMap<>();

    public String getWorkerHostId() {
        return workerHostId;
    }

    public void setWorkerHostId(String workerHostId) {
        this.workerHostId = workerHostId;
    }

    public String getHostUrl() {
        return hostUrl;
    }

    public void setHostUrl(String hostUrl) {
        this.hostUrl = hostUrl;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getInstall() {
        return install;
    }

    public void setInstall(String install) {
        this.install = install;
    }

    public Map<String, WorkerSpec> getWorkers() {
        return workers;
    }

    public void setWorkers(Map<String, WorkerSpec> workers) {
        this.workers = workers;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class WorkerSpec {
        private Boolean enabled;
        private Integer port;
        private String workerId;
        private String name;
        private String authMode;
        private String authToken;
        private String authTokenEnv;
        private String identityToken;
        private String identityTokenEnv;
        private String version;
        private String model;
        private String baseUrlOverride;

        public Boolean getEnabled() {
            return enabled;
        }

        public void setEnabled(Boolean enabled) {
            this.enabled = enabled;
        }

        public Integer getPort() {
            return port;
        }

        public void setPort(Integer port) {
            this.port = port;
        }

        public String getWorkerId() {
            return workerId;
        }

        public void setWorkerId(String workerId) {
            this.workerId = workerId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAuthMode() {
            return authMode;
        }

        public void setAuthMode(String authMode) {
            this.authMode = authMode;
        }

        public String getAuthToken() {
            return authToken;
        }

        public void setAuthToken(String authToken) {
            this.authToken = authToken;
        }

        public String getAuthTokenEnv() {
            return authTokenEnv;
        }

        public void setAuthTokenEnv(String authTokenEnv) {
            this.authTokenEnv = authTokenEnv;
        }

        public String getIdentityToken() {
            return identityToken;
        }

        public void setIdentityToken(String identityToken) {
            this.identityToken = identityToken;
        }

        public String getIdentityTokenEnv() {
            return identityTokenEnv;
        }

        public void setIdentityTokenEnv(String identityTokenEnv) {
            this.identityTokenEnv = identityTokenEnv;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrlOverride() {
            return baseUrlOverride;
        }

        public void setBaseUrlOverride(String baseUrlOverride) {
            this.baseUrlOverride = baseUrlOverride;
        }
    }
}
