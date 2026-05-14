package com.foggy.navigator.sdk.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * 任务增量消息分页结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TaskMessagesPage {
    private String taskId;
    private String contextId;
    private String status;
    private List<SessionMessage> messages;
    private String nextCursor;
    private boolean hasMore;
    private boolean terminal;
    private String terminalStatus;

    public String getTaskId() { return taskId; }
    public void setTaskId(String taskId) { this.taskId = taskId; }
    public String getContextId() { return contextId; }
    public void setContextId(String contextId) { this.contextId = contextId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public List<SessionMessage> getMessages() { return messages; }
    public void setMessages(List<SessionMessage> messages) { this.messages = messages; }
    public String getNextCursor() { return nextCursor; }
    public void setNextCursor(String nextCursor) { this.nextCursor = nextCursor; }
    public boolean isHasMore() { return hasMore; }
    public void setHasMore(boolean hasMore) { this.hasMore = hasMore; }
    public boolean isTerminal() { return terminal; }
    public void setTerminal(boolean terminal) { this.terminal = terminal; }
    public String getTerminalStatus() { return terminalStatus; }
    public void setTerminalStatus(String terminalStatus) { this.terminalStatus = terminalStatus; }
}
