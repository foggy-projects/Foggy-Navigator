package com.foggy.navigator.agent.protocol.action;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户操作请求载荷
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserActionRequestPayload {
    private String actionId;
    private ActionType actionType;
    private ConfirmationConfig confirmation;
    private FormConfig form;
    private SelectionConfig selection;
    private Integer timeoutSeconds;
    private String timeoutAction;
}
