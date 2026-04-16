package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 会话消息列表分页结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionMessagesPage {
    private String contextId;
    private List<SessionMessage> messages;
    private String nextCursor;
    private boolean hasMore;

    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public List<SessionMessage> getMessages() { return messages; }
    public void setMessages(List<SessionMessage> messages) { this.messages = messages; }
    public String getNextCursor() { return nextCursor; }
    public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
}
