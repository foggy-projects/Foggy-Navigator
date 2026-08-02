package com.foggy.navigator.claude.worker.controller.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionMessageDTO;
import com.foggy.navigator.claude.worker.model.dto.OpenSessionSummaryDTO;
import com.foggy.navigator.common.entity.AgentConversationContextEntity;
import com.foggy.navigator.common.entity.SessionMessageEntity;
import com.foggy.navigator.common.entity.SessionTaskEntity;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenApiSessionProjectionMapperTest {

    private final OpenApiSessionProjectionMapper mapper =
            new OpenApiSessionProjectionMapper(new ObjectMapper());

    @Test
    void mapsTerminalMessageAndSanitizedStructuredOutputWithoutMutatingMetadataShape() {
        SessionMessageEntity message = message(
                "ASSISTANT",
                "done",
                """
                        {
                          "type":"TASK_COMPLETED",
                          "taskId":"task-1",
                          "structuredOutput":{
                            "type":"OPEN_ARTIFACT",
                            "token":"token=raw-secret"
                          }
                        }
                        """);

        OpenSessionMessageDTO result = mapper.mapMessage(message, "ctx-1", "COMPLETED");

        assertEquals("RESULT", result.getType());
        assertEquals("final_marker", result.getEventKind());
        assertEquals("COMPLETED", result.getStatus());
        assertTrue(result.getTerminal());
        assertEquals("COMPLETED", result.getTerminalStatus());
        assertNull(result.getMetadata().get("taskId"));
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result.getStructuredOutput();
        assertEquals("OPEN_ARTIFACT", structured.get("type"));
        assertEquals("token=[REDACTED]", structured.get("token"));
        @SuppressWarnings("unchecked")
        Map<String, Object> originalMetadataShape =
                (Map<String, Object>) result.getMetadata().get("structuredOutput");
        assertEquals("token=raw-secret", originalMetadataShape.get("token"));
    }

    @Test
    void owningTaskStatusNeverMakesToolMessageTerminalAndMetadataTypeRemainsCaseSensitive() {
        SessionMessageEntity tool = message(
                "tOoL",
                "tool result",
                "{\"type\":\"TOOL_CALL_RESULT\"}");
        OpenSessionMessageDTO toolResult = mapper.mapMessage(tool, "ctx-1", "COMPLETED");

        assertEquals("tool", toolResult.getRole());
        assertEquals("TOOL_RESULT", toolResult.getType());
        assertEquals("tool_result_summary", toolResult.getEventKind());
        assertEquals("COMPLETED", toolResult.getStatus());
        assertFalse(toolResult.getTerminal());
        assertNull(toolResult.getTerminalStatus());

        SessionMessageEntity lowerCaseTerminal = message(
                "assistant",
                "not a canonical marker",
                "{\"type\":\"task_completed\"}");
        OpenSessionMessageDTO lowerCaseResult =
                mapper.mapMessage(lowerCaseTerminal, "ctx-1", "FUTURE_STATUS");

        assertEquals("TEXT", lowerCaseResult.getType());
        assertEquals("final_marker", lowerCaseResult.getEventKind());
        assertEquals("FUTURE_STATUS", lowerCaseResult.getStatus());
        assertFalse(lowerCaseResult.getTerminal());
        assertNull(lowerCaseResult.getTerminalStatus());
    }

    @Test
    void mapsProgressAttachmentsAndStableEvidenceReferenceOrder() {
        String longPath = "/workspace/" + "x".repeat(340) + "?signature=secret";
        SessionMessageEntity message = message(
                "SYSTEM",
                "opening frame",
                """
                        {
                          "type":"STATE_SYNC",
                          "subtype":"skillFrameOpen",
                          "attachments":[
                            {"id":"att-1","name":"one.png"},
                            "ignored",
                            {"id":"att-2","name":"two.png"}
                          ],
                          "reportRefs":[
                            "frame-report://worker-task/frame-1",
                            "frame-report://worker-task/frame-1",
                            {"ref":"report://summary","summary":"ready"}
                          ],
                          "artifactRefs":[
                            {"ref":"artifact://one?token=secret","path":"/ignored"},
                            {"ref":"artifact://one?token=other","path":"/duplicate"},
                            {"path":"%s"}
                          ]
                        }
                        """.formatted(longPath));

        OpenSessionMessageDTO result = mapper.mapMessage(message, "ctx-1", "RUNNING");

        assertEquals("STATE", result.getType());
        assertEquals("progress", result.getEventKind());
        assertEquals("skill_frame_open", result.getProgressType());
        assertEquals("RUNNING", result.getStatus());
        assertEquals("one.png", result.getAttachments().get(0).get("name"));
        assertEquals("two.png", result.getAttachments().get(1).get("name"));
        assertEquals(2, result.getReportRefs().size());
        assertEquals("frame_report", result.getReportRefs().get(0).getType());
        assertEquals("frame-1", result.getReportRefs().get(0).getFrameId());
        assertEquals("report://summary", result.getReportRefs().get(1).getRef());
        assertEquals(2, result.getArtifactRefs().size());
        assertEquals("artifact://one", result.getArtifactRefs().get(0).getRef());
        assertEquals(300, result.getArtifactRefs().get(1).getPath().length());
        assertFalse(result.getArtifactRefs().get(1).getPath().contains("?"));
    }

    @Test
    void malformedAndEmptyMetadataRemainDistinctNonterminalProjections() {
        OpenSessionMessageDTO malformed = mapper.mapMessage(
                message("aSsIsTaNt", "plain", "{not-json"),
                "ctx-1",
                null);
        assertEquals("assistant", malformed.getRole());
        assertEquals("TEXT", malformed.getType());
        assertEquals("text_complete", malformed.getEventKind());
        assertFalse(malformed.getTerminal());
        assertNull(malformed.getStatus());
        assertNull(malformed.getMetadata());
        assertNull(malformed.getAttachments());
        assertNull(malformed.getReportRefs());
        assertNull(malformed.getArtifactRefs());

        OpenSessionMessageDTO empty = mapper.mapMessage(
                message("uSeR", "hello", "{}"),
                "ctx-1",
                "UNKNOWN_PROVIDER_STATUS");
        assertEquals("user", empty.getRole());
        assertEquals("USER", empty.getType());
        assertEquals("user_message", empty.getEventKind());
        assertEquals("UNKNOWN_PROVIDER_STATUS", empty.getStatus());
        assertNotNull(empty.getMetadata());
        assertTrue(empty.getMetadata().isEmpty());
    }

    @Test
    void mapsSyntheticFailureWithCallerComputedStatusAndFallbackSummary() {
        SessionTaskEntity task = new SessionTaskEntity();
        task.setTaskId("task-failed");
        task.setWorkerId("worker-1");
        task.setProviderType("OPENAI_CODEX");
        task.setCreatedAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        task.setUpdatedAt(LocalDateTime.of(2026, 8, 3, 9, 1));

        OpenSessionMessageDTO result = mapper.mapSyntheticTaskError(
                task,
                "ctx-1",
                "FAILED",
                "FAILED",
                null,
                "WORKER_TRANSPORT");

        assertEquals("task-error:task-failed", result.getMessageId());
        assertEquals("Task failed without persisted runtime messages.", result.getContent());
        assertEquals("FAILED", result.getStatus());
        assertTrue(result.getTerminal());
        assertEquals("FAILED", result.getTerminalStatus());
        assertEquals("task_state", result.getMetadata().get("source"));
        assertEquals("WORKER_TRANSPORT", result.getMetadata().get("failureStage"));
        assertEquals("worker-1", result.getMetadata().get("workerId"));
        assertEquals(task.getUpdatedAt(), result.getCreatedAt());
    }

    @Test
    void mapsActiveSummaryWithAliasOrFirstUserMessageAndMalformedContextFailsClosed() {
        AgentConversationContextEntity aliased = context("alias", "{\"conversationId\":\"tms-1\"}");
        OpenSessionSummaryDTO aliasedResult = mapper.mapSummary(
                aliased,
                "agent-1",
                Map.of("session-1", "task-1"),
                Map.of("session-1", "ignored first message"));

        assertEquals("ACTIVE", aliasedResult.getStatus());
        assertEquals("alias", aliasedResult.getTitle());
        assertEquals("task-1", aliasedResult.getLatestTaskId());
        assertEquals("tms-1", aliasedResult.getClientContext().get("conversationId"));

        AgentConversationContextEntity untitled = context(null, "{bad-json");
        String longTitle = "题".repeat(140);
        OpenSessionSummaryDTO fallbackResult = mapper.mapSummary(
                untitled,
                "agent-1",
                Map.of(),
                Map.of("session-1", longTitle));

        assertEquals(120, fallbackResult.getTitle().length());
        assertEquals("ACTIVE", fallbackResult.getStatus());
        assertNull(fallbackResult.getLatestTaskId());
        assertNull(fallbackResult.getClientContext());
    }

    private SessionMessageEntity message(String role, String content, String metadata) {
        SessionMessageEntity message = new SessionMessageEntity();
        message.setId("message-1");
        message.setTaskId("task-1");
        message.setSessionId("session-1");
        message.setRole(role);
        message.setContent(content);
        message.setMetadata(metadata);
        message.setCreatedAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        return message;
    }

    private AgentConversationContextEntity context(String alias, String clientContextJson) {
        AgentConversationContextEntity context = new AgentConversationContextEntity();
        context.setContextId("ctx-1");
        context.setContextAlias(alias);
        context.setNavigatorSessionId("session-1");
        context.setClientContextJson(clientContextJson);
        context.setCreatedAt(LocalDateTime.of(2026, 8, 3, 9, 0));
        context.setLastAccessedAt(LocalDateTime.of(2026, 8, 3, 9, 1));
        return context;
    }
}
