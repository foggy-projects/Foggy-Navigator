package com.foggy.navigator.workbench.fap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.foggy.agent.client.CreateTaskCommand;
import com.foggy.agent.client.CreateTaskResult;
import com.foggy.agent.client.FoggyAgentPlatformClient;
import com.foggy.agent.client.PlatformClientException;
import com.foggy.agent.client.StartExecutionCommand;
import com.foggy.agent.client.StartExecutionResult;
import com.foggy.agent.contract.runtime.v1alpha1.CreateExecutionAccepted;
import com.foggy.agent.contract.runtime.v1alpha1.CreateTaskAccepted;
import com.foggy.navigator.workbench.fap.config.WorkbenchFapProperties;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.ContinueConversationForm;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.StartConversationForm;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity.BindingStatus;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingRepository;
import com.foggy.navigator.workbench.fap.web.WorkbenchFapException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class WorkbenchFapServiceTest {
    private static final String OWNER = "owner-1";

    @Mock
    private WorkbenchFapConversationBindingRepository bindings;

    @Mock
    private FoggyAgentPlatformClient platform;

    private ObjectMapper mapper;
    private WorkbenchFapService service;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper().findAndRegisterModules();
        WorkbenchFapProperties properties = new WorkbenchFapProperties();
        properties.setEnabled(true);
        properties.setOwnerUserIds(Set.of(OWNER));
        lenient().when(bindings.saveAndFlush(any()))
                .thenAnswer(invocation -> invocation.getArgument(0));
        service = new WorkbenchFapService(
                bindings,
                platform,
                properties,
                new WorkbenchFapCommandMapper(properties, mapper),
                new WorkbenchFapProjectionMapper());
    }

    @Test
    void startsNewFapLaneAndPersistsOnlySafeBindingAndEffectiveScopes() {
        when(bindings.findByOwnerUserIdAndStartRequestId(OWNER, "start-1"))
                .thenReturn(Optional.empty());
        when(platform.start(any())).thenReturn(startResult());

        var view = service.start(
                OWNER,
                new StartConversationForm(
                        "start-1",
                        null,
                        "codex-profile",
                        "workspace-profile",
                        "model-profile",
                        false,
                        "implement focused change",
                        null));

        assertThat(view.executionLane()).isEqualTo("FAP_V1");
        assertThat(view.bindingStatus()).isEqualTo("ACTIVE");
        assertThat(view.executionId()).isEqualTo("execution-1");
        assertThat(view.currentTaskId()).isEqualTo("task-1");
        ArgumentCaptor<StartExecutionCommand> command =
                ArgumentCaptor.forClass(StartExecutionCommand.class);
        verify(platform).start(command.capture());
        assertThat(command.getValue().caller().internalPrincipalRef())
                .isEqualTo("navigator-user:" + OWNER);
        assertThat(command.getValue().caller().externalConversationRef())
                .isEqualTo(view.conversationId());
        assertThat(command.getValue().selection().workerProfileRef())
                .isEqualTo("codex-profile");
        assertThat(command.getValue().selection().workingDirectory()).isNull();

        ArgumentCaptor<WorkbenchFapConversationBindingEntity> binding =
                ArgumentCaptor.forClass(WorkbenchFapConversationBindingEntity.class);
        verify(bindings, org.mockito.Mockito.times(2)).saveAndFlush(binding.capture());
        WorkbenchFapConversationBindingEntity persisted = binding.getValue();
        assertThat(persisted.getExecutionLane()).isEqualTo("FAP_V1");
        assertThat(persisted.getEffectiveToolScope()).contains("shell.read");
        assertThat(persisted.getEffectivePermissionScope()).contains("workspace.read");
    }

    @Test
    void continuationReusesFrozenScopesAndExactRuntimeExecution() {
        WorkbenchFapConversationBindingEntity binding = activeBinding();
        when(bindings.findOwnedForUpdate("conversation-1", OWNER))
                .thenReturn(Optional.of(binding));
        when(platform.createTask(any())).thenReturn(new CreateTaskResult(
                "decision-task",
                List.of(),
                new CreateTaskAccepted(
                        "execution-1",
                        "task-2",
                        1L,
                        lifecycle("PENDING"),
                        OffsetDateTime.parse("2026-08-04T23:00:00Z"))));

        var view = service.continueConversation(
                OWNER,
                "conversation-1",
                new ContinueConversationForm("continue-1", "continue", null));

        ArgumentCaptor<CreateTaskCommand> command =
                ArgumentCaptor.forClass(CreateTaskCommand.class);
        verify(platform).createTask(command.capture());
        assertThat(command.getValue().executionId()).isEqualTo("execution-1");
        assertThat(command.getValue().taskType()).isEqualTo("CONTINUE");
        assertThat(command.getValue().requestedToolScope().scope().path("allowed"))
                .extracting(JsonNode::asText)
                .containsExactly("shell.read");
        assertThat(command.getValue().requestedPermissionScope().scope().path("allowed"))
                .extracting(JsonNode::asText)
                .containsExactly("workspace.read");
        assertThat(view.currentTaskId()).isEqualTo("task-2");
        assertThat(binding.getLastTaskRequestId()).isEqualTo("continue-1");
    }

    @Test
    void ambiguousStartIsPersistedAsUnknownAndNeverFallsBack() {
        when(bindings.findByOwnerUserIdAndStartRequestId(OWNER, "start-unknown"))
                .thenReturn(Optional.empty());
        PlatformClientException platformError = mock(PlatformClientException.class);
        when(platformError.status()).thenReturn(0);
        when(platformError.code()).thenReturn("PLATFORM_UNAVAILABLE");
        when(platformError.retryable()).thenReturn(true);
        when(platformError.getMessage()).thenReturn("platform unavailable");
        when(platform.start(any())).thenThrow(platformError);

        assertThatThrownBy(() -> service.start(
                        OWNER,
                        new StartConversationForm(
                                "start-unknown",
                                null,
                                "codex-profile",
                                "workspace-profile",
                                null,
                                true,
                                "start",
                                null)))
                .isInstanceOf(WorkbenchFapException.class)
                .extracting(error -> ((WorkbenchFapException) error).code())
                .isEqualTo("PLATFORM_UNAVAILABLE");

        ArgumentCaptor<WorkbenchFapConversationBindingEntity> binding =
                ArgumentCaptor.forClass(WorkbenchFapConversationBindingEntity.class);
        verify(bindings, org.mockito.Mockito.times(2)).saveAndFlush(binding.capture());
        assertThat(binding.getValue().getBindingStatus())
                .isEqualTo(BindingStatus.START_OUTCOME_UNKNOWN);
        assertThat(binding.getValue().getExecutionId()).isNull();
    }

    @Test
    void eventTransportRemovesEnvelopeTicketRefsWithoutEditingProviderPayload() {
        WorkbenchFapConversationBindingEntity binding = activeBinding();
        when(bindings.findByConversationIdAndOwnerUserId("conversation-1", OWNER))
                .thenReturn(Optional.of(binding));
        ObjectNode page = mapper.createObjectNode();
        ObjectNode event = page.putArray("events").addObject();
        event.put("workerConversationId", "worker-conversation-1");
        event.put("operationTicketId", "ticket-1");
        event.putObject("payload").put("workerId", "provider-content-must-remain");
        event.putArray("resourceRefs").addObject().put("producerTicketId", "ticket-1");
        when(platform.events("execution-1", 0, 100)).thenReturn(page);

        JsonNode result = service.events(OWNER, "conversation-1", 0, 100);

        JsonNode safeEvent = result.path("events").get(0);
        assertThat(safeEvent.has("workerConversationId")).isFalse();
        assertThat(safeEvent.has("operationTicketId")).isFalse();
        assertThat(safeEvent.path("resourceRefs").get(0).has("producerTicketId"))
                .isFalse();
        assertThat(safeEvent.path("payload").path("workerId").asText())
                .isEqualTo("provider-content-must-remain");
    }

    @Test
    void resourceTransportSanitizesCanonicalItemsCollection() {
        WorkbenchFapConversationBindingEntity binding = activeBinding();
        when(bindings.findByConversationIdAndOwnerUserId("conversation-1", OWNER))
                .thenReturn(Optional.of(binding));
        ObjectNode page = mapper.createObjectNode();
        ObjectNode resource = page.putArray("items").addObject();
        resource.put("resourceId", "resource-1");
        resource.put("kind", "FINAL_OUTPUT");
        resource.put("producerTicketId", "ticket-private");
        when(platform.resources("execution-1")).thenReturn(page);

        JsonNode result = service.resources(OWNER, "conversation-1");

        assertThat(result.path("items").get(0).path("resourceId").asText())
                .isEqualTo("resource-1");
        assertThat(result.path("items").get(0).has("producerTicketId")).isFalse();
    }

    @Test
    void recoveryTransportKeepsDecisionsButRemovesWorkerCapabilitiesAndProviderResumeId() {
        WorkbenchFapConversationBindingEntity binding = activeBinding();
        when(bindings.findByConversationIdAndOwnerUserId("conversation-1", OWNER))
                .thenReturn(Optional.of(binding));
        ObjectNode snapshot = mapper.createObjectNode();
        snapshot.put("workerId", "worker-private");
        snapshot.put("workerConversationId", "worker-conversation-private");
        snapshot.putObject("activePrimaryTicketRef").put("operationTicketId", "ticket-private");
        snapshot.put("lastTerminalReceiptRef", "receipt-private");
        snapshot.putObject("reattach").put("available", true).put("reasonCode", "STATE_LOADED");
        snapshot.putObject("resume")
                .put("available", true)
                .put("reasonCode", "PROVIDER_CONVERSATION_AVAILABLE")
                .put("resumePointRef", "provider-conversation-private");
        snapshot.putArray("resourceRefs").addObject()
                .put("resourceId", "resource-1")
                .put("producerTicketId", "ticket-private");
        when(platform.recovery("execution-1")).thenReturn(snapshot);

        JsonNode result = service.recovery(OWNER, "conversation-1");

        assertThat(result.path("reattach").path("available").asBoolean()).isTrue();
        assertThat(result.has("workerId")).isFalse();
        assertThat(result.has("workerConversationId")).isFalse();
        assertThat(result.has("activePrimaryTicketRef")).isFalse();
        assertThat(result.has("lastTerminalReceiptRef")).isFalse();
        assertThat(result.path("resume").has("resumePointRef")).isFalse();
        assertThat(result.path("resourceRefs").get(0).has("producerTicketId")).isFalse();
    }

    private StartExecutionResult startResult() {
        return new StartExecutionResult(
                "decision-start",
                List.of(),
                scope("ALLOW_LIST", "shell.read"),
                scope("RESTRICTED", "workspace.read"),
                new CreateExecutionAccepted(
                        "execution-1",
                        "task-1",
                        1L,
                        lifecycle("PENDING"),
                        OffsetDateTime.parse("2026-08-04T23:00:00Z")));
    }

    private WorkbenchFapConversationBindingEntity activeBinding() {
        WorkbenchFapConversationBindingEntity binding =
                WorkbenchFapConversationBindingEntity.starting(
                        "conversation-1",
                        OWNER,
                        "start-1",
                        "Focused task",
                        "codex-profile",
                        "workspace-profile",
                        "model-profile",
                        false);
        binding.activate(
                "execution-1",
                "task-1",
                json(scope("ALLOW_LIST", "shell.read")),
                json(scope("RESTRICTED", "workspace.read")));
        return binding;
    }

    private ObjectNode scope(String mode, String value) {
        ObjectNode scope = mapper.createObjectNode();
        scope.put("mode", mode);
        scope.putArray("allowed").add(value);
        return scope;
    }

    private ObjectNode lifecycle(String displayState) {
        return mapper.createObjectNode()
                .put("coordinationState", "ACCEPTED")
                .put("displayState", displayState)
                .put("definitiveTerminal", false);
    }

    private String json(JsonNode value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
