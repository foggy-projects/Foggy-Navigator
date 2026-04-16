package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 会话列表分页结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class SessionListPage {
    private List<SessionSummary> sessions;
    private String nextCursor;
    private boolean hasMore;

    public List<SessionSummary> getSessions() { return sessions; }
    public void setSessions(List<SessionSummary> sessions) { this.sessions = sessions; }
    public String getNextCursor() { return nextCursor; }
    public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
}
