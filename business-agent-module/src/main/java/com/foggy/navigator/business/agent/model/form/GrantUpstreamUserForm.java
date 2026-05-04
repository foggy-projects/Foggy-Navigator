package com.foggy.navigator.business.agent.model.form;

import lombok.Data;

@Data
public class GrantUpstreamUserForm {
    private String upstreamUserId;
    private String upstreamUserToken;
    private String status;
}
