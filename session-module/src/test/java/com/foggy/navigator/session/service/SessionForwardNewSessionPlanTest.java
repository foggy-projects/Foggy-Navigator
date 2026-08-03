package com.foggy.navigator.session.service;

import com.foggy.navigator.spi.agent.AgentResolveContext;
import com.foggy.navigator.spi.agent.AgentTaskSubmitRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionForwardNewSessionPlanTest {

    private static final String CLIENT_REQUEST_ID = "5f5a402e-8506-4735-8441-1cbaca240627";
    private static final String TARGET_SESSION_ID =
            SessionForwardTargetSessionReservationService.deriveSessionId(
                    CLIENT_REQUEST_ID, "owner-1", "tenant-1");

    @Test
    void projectsExactReservationAndSubmitRequest() {
        SessionForwardNewSessionPlan plan = plan();

        var reservation = plan.reservationSpec();
        assertEquals("owner-1", reservation.ownerUserId());
        assertEquals("tenant-1", reservation.tenantId());
        assertEquals("agent-1", reservation.logicalAgentId());
        assertEquals("root-session-1", reservation.rootParentSessionId());
        assertEquals("Forward the verified result", reservation.title());
        assertEquals("directory-1", reservation.directoryId());
        assertEquals("milestone-1", reservation.milestoneId());
        assertEquals("gpt-5.6", reservation.model());

        AgentTaskSubmitRequest submit = plan.toSubmitRequest(
                CLIENT_REQUEST_ID, TARGET_SESSION_ID);
        assertEquals("agent-1", submit.getAgentId());
        assertNull(submit.getProviderType());
        assertEquals(TARGET_SESSION_ID, submit.getSessionId());
        assertEquals("worker-1", submit.getWorkerId());
        assertEquals("Forward the verified result", submit.getPrompt());
        assertEquals("/workspace/project", submit.getCwd());
        assertEquals("directory-1", submit.getDirectoryId());
        assertEquals("gpt-5.6", submit.getModel());
        assertEquals("model-config-1", submit.getModelConfigId());
        assertEquals(7, submit.getMaxTurns());
        assertEquals("workspace-write", submit.getPermissionMode());
        assertEquals(List.of("[{\"image\":\"base64-payload\"}]"), submit.getImages());
        assertNull(submit.getAttachments());
        assertEquals("teams-config-1", submit.getAgentTeamsConfigId());
        assertEquals("{\"team\":\"alpha\"}", submit.getAgentTeamsJson());
        assertNull(submit.getContextId());
        assertNull(submit.getContext());
        assertNull(submit.getContextAlias());
        assertNull(submit.getMetadata());
        assertEquals(CLIENT_REQUEST_ID, submit.getClientRequestId());
        assertTrue(submit.isInitializeRuntimeAffinity());

        AgentResolveContext context = submit.getResolveContext();
        assertEquals("owner-1", context.getUserId());
        assertEquals("tenant-1", context.getTenantId());
        assertEquals(TARGET_SESSION_ID, context.getSessionId());
        assertEquals("model-config-1", context.getModelConfigId());
        assertEquals("UI_FORWARD", context.getRequestSource());
    }

    @Test
    void freezesGoldenSemanticFingerprintAndExcludesDerivedRequestIdentity() {
        SessionForwardNewSessionPlan plan = plan();

        assertEquals("b45fadfa1565054c5d4865efe1cf8263e4a78313c60382ec97c4145923678577",
                plan.semanticFingerprint());
        assertEquals(plan.semanticFingerprint(), plan().semanticFingerprint());
        assertEquals(plan.semanticFingerprint(), plan().semanticFingerprint());

        AgentTaskSubmitRequest first = plan.toSubmitRequest(
                CLIENT_REQUEST_ID, TARGET_SESSION_ID);
        AgentTaskSubmitRequest second = plan.toSubmitRequest(
                "8ca523e7-c06e-47e6-be9e-bd1169e962d9",
                SessionForwardTargetSessionReservationService.deriveSessionId(
                        "8ca523e7-c06e-47e6-be9e-bd1169e962d9",
                        "owner-1",
                        "tenant-1"));
        assertNotEquals(first.getClientRequestId(), second.getClientRequestId());
        assertNotEquals(first.getSessionId(), second.getSessionId());
        assertEquals(plan.semanticFingerprint(), plan.semanticFingerprint());
    }

    @Test
    void everyEffectBearingFactChangesSemanticFingerprint() {
        String baseline = plan().semanticFingerprint();
        Map<String, Supplier<SessionForwardNewSessionPlan>> variants = new LinkedHashMap<>();
        variants.put("owner", () -> plan("owner-2", "tenant-1", source(), "root-session-1",
                "Forward the verified result", target()));
        variants.put("tenant", () -> plan("owner-1", "tenant-2", source(), "root-session-1",
                "Forward the verified result", target()));
        variants.put("root", () -> plan("owner-1", "tenant-1", source(), "root-session-2",
                "Forward the verified result", target()));
        variants.put("prompt", () -> plan("owner-1", "tenant-1", source(), "root-session-1",
                "Forward a different result", target()));

        variants.put("sourceSession", () -> plan(source(f -> f.sessionId = "source-session-2"), target()));
        variants.put("sourceKind", () -> plan(source(f -> f.kind = SessionForwardNewSessionPlan.SourceKind.TASK_RESULT), target()));
        variants.put("sourceReference", () -> plan(source(f -> f.referenceId = "message-reference-2"), target()));
        variants.put("sourceTask", () -> plan(source(f -> f.taskId = "source-task-2"), target()));
        variants.put("sourceContent", () -> plan(source(f -> f.content = "different source content"), target()));
        variants.put("sourceWorker", () -> plan(source(f -> f.workerId = "source-worker-2"), target()));
        variants.put("sourceDirectory", () -> plan(source(f -> f.directoryId = "source-directory-2"), target()));
        variants.put("sourceMilestone", () -> plan(source(f -> f.milestoneId = "source-milestone-2"), target()));

        variants.put("worker", () -> plan(source(), target(f -> f.workerId = "worker-2")));
        variants.put("directory", () -> plan(source(), target(f -> f.directoryId = "directory-2")));
        variants.put("cwd", () -> plan(source(), target(f -> f.cwd = "/workspace/other")));
        variants.put("agent", () -> plan(source(), target(f -> f.logicalAgentId = "agent-2")));
        variants.put("milestone", () -> plan(source(), target(f -> f.milestoneId = "milestone-2")));
        variants.put("model", () -> plan(source(), target(f -> f.model = "gpt-5.7")));
        variants.put("modelConfig", () -> plan(source(), target(f -> f.modelConfigId = "model-config-2")));
        variants.put("permission", () -> plan(source(), target(f -> f.permissionMode = "read-only")));
        variants.put("maxTurns", () -> plan(source(), target(f -> f.maxTurns = 8)));
        variants.put("teamsConfig", () -> plan(source(), target(f -> f.agentTeamsConfigId = "teams-config-2")));
        variants.put("teamsJson", () -> plan(source(), target(f -> f.agentTeamsJson = "{\"team\":\"beta\"}")));
        variants.put("images", () -> plan(source(), target(f -> f.images = List.of("other-image"))));

        variants.forEach((field, variant) -> assertNotEquals(
                baseline, variant.get().semanticFingerprint(), field));
    }

    @Test
    void preservesNullEmptyListAndNullableIntegerDistinctions() {
        String nullImages = plan(source(), target(f -> f.images = null)).semanticFingerprint();
        String emptyImages = plan(source(), target(f -> f.images = List.of())).semanticFingerprint();
        String oneImage = plan(source(), target(f -> f.images = List.of("image"))).semanticFingerprint();
        assertNotEquals(nullImages, emptyImages);
        assertNotEquals(emptyImages, oneImage);
        assertNotEquals(nullImages, oneImage);

        String nullTurns = plan(source(), target(f -> f.maxTurns = null)).semanticFingerprint();
        String zeroTurns = plan(source(), target(f -> f.maxTurns = 0)).semanticFingerprint();
        String negativeTurns = plan(source(), target(f -> f.maxTurns = -1)).semanticFingerprint();
        String positiveTurns = plan(source(), target(f -> f.maxTurns = 7)).semanticFingerprint();
        assertEquals(4, List.of(nullTurns, zeroTurns, negativeTurns, positiveTurns)
                .stream().distinct().count());
    }

    @Test
    void normalizesReferencesAndPreservesOpaquePayloadBytes() {
        SessionForwardNewSessionPlan normalized = plan();
        SessionForwardNewSessionPlan paddedReferences = plan(
                " owner-1 ",
                " tenant-1 ",
                source(f -> {
                    f.sessionId = " source-session-1 ";
                    f.referenceId = " message-reference-1 ";
                    f.taskId = " source-task-1 ";
                }),
                " root-session-1 ",
                "Forward the verified result",
                target(f -> {
                    f.workerId = " worker-1 ";
                    f.directoryId = " directory-1 ";
                    f.logicalAgentId = " agent-1 ";
                    f.milestoneId = " milestone-1 ";
                    f.model = " gpt-5.6 ";
                    f.modelConfigId = " model-config-1 ";
                    f.permissionMode = " workspace-write ";
                    f.agentTeamsConfigId = " teams-config-1 ";
                }));
        assertEquals(normalized.semanticFingerprint(), paddedReferences.semanticFingerprint());

        SessionForwardNewSessionPlan opaqueDrift = plan(
                source(f -> f.content = " Source result bytes "),
                target(f -> f.agentTeamsJson = " {\"team\":\"alpha\"} "));
        assertNotEquals(normalized.semanticFingerprint(), opaqueDrift.semanticFingerprint());
        assertNotEquals(normalized.semanticFingerprint(),
                plan(source(), target(f -> f.cwd = " /workspace/project "))
                        .semanticFingerprint());
        assertNotEquals(normalized.semanticFingerprint(),
                plan(source(), target(f -> f.agentTeamsJson = " {\"team\":\"alpha\"} "))
                        .semanticFingerprint());

        assertNull(SessionForwardNewSessionPlan.imagesFromWire("  "));
        assertEquals(List.of("[{\"x\":1}]"),
                SessionForwardNewSessionPlan.imagesFromWire("  [{\"x\":1}]  "));

        SessionForwardNewSessionPlan unicode = plan(
                source(f -> f.content = "结果😀"),
                target(f -> {
                    f.cwd = "/工作区/项目😀";
                    f.agentTeamsJson = "{\"团队\":\"甲😀\"}";
                    f.images = List.of("图像😀");
                }));
        assertNotEquals(normalized.semanticFingerprint(), unicode.semanticFingerprint());
        assertEquals("结果😀", unicode.source().content());
    }

    @Test
    void copiesImagesAndRedactsContentFromStringRepresentations() {
        List<String> mutableImages = new ArrayList<>();
        mutableImages.add("[{\"secretImage\":\"payload\"}]");
        SessionForwardNewSessionPlan.TargetExecution target = target(f -> {
            f.images = mutableImages;
            f.agentTeamsJson = "{\"secretTeam\":\"payload\"}";
        });
        SessionForwardNewSessionPlan plan = plan(
                source(f -> f.content = "secret-source-content"), target);

        mutableImages.add("late-mutation");
        assertEquals(1, plan.target().images().size());
        assertThrows(UnsupportedOperationException.class,
                () -> plan.target().images().add("mutation"));

        String planText = plan.toString();
        String sourceText = plan.source().toString();
        String targetText = plan.target().toString();
        for (String secret : List.of(
                "Forward the verified result",
                "secret-source-content",
                "secretImage",
                "secretTeam")) {
            assertFalse(planText.contains(secret));
            assertFalse(sourceText.contains(secret));
            assertFalse(targetText.contains(secret));
        }
        assertTrue(planText.contains("prompt=<redacted>"));
    }

    @Test
    void derivesTitleWithoutTruncatingSubmittedPrompt() {
        String prompt = "p".repeat(121);
        SessionForwardNewSessionPlan plan = plan(
                "owner-1", "tenant-1", source(), "root-session-1", prompt, target());

        assertEquals(120, plan.sessionTitle().length());
        assertEquals(prompt, plan.toSubmitRequest(CLIENT_REQUEST_ID, TARGET_SESSION_ID).getPrompt());

        String supplementaryBoundary = "p".repeat(119) + "😀" + "tail";
        SessionForwardNewSessionPlan boundaryPlan = plan(
                "owner-1",
                "tenant-1",
                source(),
                "root-session-1",
                supplementaryBoundary,
                target());
        assertEquals("p".repeat(119), boundaryPlan.sessionTitle());
        assertEquals(boundaryPlan.sessionTitle(), boundaryPlan.reservationSpec().title());
        assertEquals(64, boundaryPlan.semanticFingerprint().length());
        assertEquals(supplementaryBoundary,
                boundaryPlan.toSubmitRequest(CLIENT_REQUEST_ID, TARGET_SESSION_ID).getPrompt());
    }

    @Test
    void rejectsInvalidRequiredAndReferenceFactsBeforeProjection() {
        assertThrows(IllegalArgumentException.class,
                () -> plan(" ", "tenant-1", source(), "root-session-1", "prompt", target()));
        assertThrows(IllegalArgumentException.class,
                () -> plan("owner-1", "tenant-1", source(), " ", "prompt", target()));
        assertThrows(IllegalArgumentException.class,
                () -> plan("owner-1", "tenant-1", source(), "root-session-1", " \n ", target()));
        assertThrows(IllegalArgumentException.class,
                () -> source(f -> f.sessionId = " "));
        assertThrows(IllegalArgumentException.class,
                () -> source(f -> f.referenceId = "bad\nreference"));
        assertThrows(IllegalArgumentException.class,
                () -> source(f -> {
                    f.kind = SessionForwardNewSessionPlan.SourceKind.TASK_RESULT;
                    f.taskId = null;
                }));
        assertThrows(IllegalArgumentException.class,
                () -> target(f -> f.workerId = " "));
        assertThrows(IllegalArgumentException.class,
                () -> target(f -> f.model = "m".repeat(129)));
        assertThrows(IllegalArgumentException.class,
                () -> target(f -> f.directoryId = null));
        SessionForwardNewSessionPlan.TargetExecution directoryFreeWorker = target(f -> {
            f.logicalAgentId = null;
            f.directoryId = null;
        });
        assertNull(directoryFreeWorker.directoryId());
        assertNull(directoryFreeWorker.logicalAgentId());
        assertThrows(IllegalArgumentException.class,
                () -> plan("owner-1", "tenant-1", source(), "root-session-1",
                        "bad\ud800prompt", target()));
        assertThrows(IllegalArgumentException.class,
                () -> source(f -> f.content = "bad\ud800content"));
        assertThrows(IllegalArgumentException.class,
                () -> target(f -> f.cwd = "bad\ud800cwd"));
        assertThrows(IllegalArgumentException.class,
                () -> target(f -> f.agentTeamsJson = "bad\ud800json"));
        assertThrows(IllegalArgumentException.class,
                () -> target(f -> f.images = List.of("bad\ud800image")));
        assertThrows(IllegalArgumentException.class,
                () -> plan().toSubmitRequest("not-a-uuid", TARGET_SESSION_ID));
        assertThrows(IllegalArgumentException.class,
                () -> plan().toSubmitRequest(CLIENT_REQUEST_ID, " "));
        assertThrows(IllegalArgumentException.class,
                () -> plan().toSubmitRequest(CLIENT_REQUEST_ID, "fwd_" + "a".repeat(60)));
    }

    private static SessionForwardNewSessionPlan plan() {
        return plan(source(), target());
    }

    private static SessionForwardNewSessionPlan plan(
            SessionForwardNewSessionPlan.SourceSnapshot source,
            SessionForwardNewSessionPlan.TargetExecution target) {
        return plan(
                "owner-1",
                "tenant-1",
                source,
                "root-session-1",
                "Forward the verified result",
                target);
    }

    private static SessionForwardNewSessionPlan plan(
            String owner,
            String tenant,
            SessionForwardNewSessionPlan.SourceSnapshot source,
            String root,
            String prompt,
            SessionForwardNewSessionPlan.TargetExecution target) {
        return new SessionForwardNewSessionPlan(owner, tenant, source, root, prompt, target);
    }

    private static SessionForwardNewSessionPlan.SourceSnapshot source() {
        return source(ignored -> { });
    }

    private static SessionForwardNewSessionPlan.SourceSnapshot source(
            Consumer<SourceFixture> mutation) {
        SourceFixture fixture = new SourceFixture();
        mutation.accept(fixture);
        return fixture.build();
    }

    private static SessionForwardNewSessionPlan.TargetExecution target() {
        return target(ignored -> { });
    }

    private static SessionForwardNewSessionPlan.TargetExecution target(
            Consumer<TargetFixture> mutation) {
        TargetFixture fixture = new TargetFixture();
        mutation.accept(fixture);
        return fixture.build();
    }

    private static final class SourceFixture {
        private String sessionId = "source-session-1";
        private SessionForwardNewSessionPlan.SourceKind kind =
                SessionForwardNewSessionPlan.SourceKind.MESSAGE;
        private String referenceId = "message-reference-1";
        private String taskId = "source-task-1";
        private String content = "Source result bytes";
        private String workerId = "source-worker-1";
        private String directoryId = "source-directory-1";
        private String milestoneId = "source-milestone-1";

        private SessionForwardNewSessionPlan.SourceSnapshot build() {
            return new SessionForwardNewSessionPlan.SourceSnapshot(
                    sessionId,
                    kind,
                    referenceId,
                    taskId,
                    content,
                    workerId,
                    directoryId,
                    milestoneId);
        }
    }

    private static final class TargetFixture {
        private String workerId = "worker-1";
        private String directoryId = "directory-1";
        private String cwd = "/workspace/project";
        private String logicalAgentId = "agent-1";
        private String milestoneId = "milestone-1";
        private String model = "gpt-5.6";
        private String modelConfigId = "model-config-1";
        private String permissionMode = "workspace-write";
        private Integer maxTurns = 7;
        private String agentTeamsConfigId = "teams-config-1";
        private String agentTeamsJson = "{\"team\":\"alpha\"}";
        private List<String> images = List.of("[{\"image\":\"base64-payload\"}]");

        private SessionForwardNewSessionPlan.TargetExecution build() {
            return new SessionForwardNewSessionPlan.TargetExecution(
                    workerId,
                    directoryId,
                    cwd,
                    logicalAgentId,
                    milestoneId,
                    model,
                    modelConfigId,
                    permissionMode,
                    maxTurns,
                    agentTeamsConfigId,
                    agentTeamsJson,
                    images);
        }
    }
}
