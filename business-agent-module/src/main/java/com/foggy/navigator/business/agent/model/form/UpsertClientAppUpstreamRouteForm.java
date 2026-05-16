package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class UpsertClientAppUpstreamRouteForm {
    private String baseUrl;
    private String userTokenHeader;
    private String status;
    private String description;
}
