package com.foggy.navigator.agent.framework.router;

import java.util.Map;

/**
 * 会话路由器
 * 负责Agent间的会话跳转和上下文传递
 */
public interface SessionRouter {

    /**
     * 分派任务给另一个Agent
     */
    DelegationResult delegateToAgent(DelegationRequest request);

    /**
     * 恢复到父会话
     */
    DelegationResult returnToParent(String currentSessionId);

    /**
     * 检查分派的前置条件
     */
    PreconditionCheckResult checkPreconditions(Map<String, Object> preconditions);
}
