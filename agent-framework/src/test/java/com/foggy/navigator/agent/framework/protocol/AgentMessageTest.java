package com.foggy.navigator.agent.framework.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.foggy.navigator.agent.framework.protocol.action.*;
import com.foggy.navigator.agent.framework.protocol.route.*;
import com.foggy.navigator.agent.framework.protocol.surface.ActionConfig;
import com.foggy.navigator.agent.framework.protocol.surface.ComponentType;
import com.foggy.navigator.agent.framework.protocol.surface.SurfaceUpdatePayload;
import com.foggy.navigator.agent.framework.protocol.surface.UiComponent;
import com.foggy.navigator.agent.protocol.route.*;
import com.foggy.navigator.agent.protocol.surface.*;
import com.foggy.navigator.agent.protocol.action.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AgentMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldCreateTextChunkMessage() {
        AgentMessage message = AgentMessage.builder()
                .type(MessageType.TEXT_CHUNK)
                .payload("Hello, world!")
                .sessionId("session-1")
                .agentId("agent-1")
                .build();

        assertEquals(MessageType.TEXT_CHUNK, message.getType());
        assertEquals("Hello, world!", message.getPayload());
        assertNotNull(message.getTimestamp());
    }

    @Test
    void shouldSerializeToJson() throws Exception {
        AgentMessage message = AgentMessage.builder()
                .type(MessageType.TEXT_CHUNK)
                .payload("Test message")
                .sessionId("session-1")
                .build();

        String json = objectMapper.writeValueAsString(message);

        assertTrue(json.contains("TEXT_CHUNK"));
        assertTrue(json.contains("Test message"));
    }

    @Test
    void shouldCreateRouteRequestMessage() {
        RoutePayload route = RoutePayload.builder()
                .action(RouteAction.DELEGATE)
                .mode(RouteMode.REPLACE)
                .target(RouteTarget.builder()
                        .agentId("target-agent")
                        .agentName("Target Agent")
                        .sessionId("new-session")
                        .build())
                .context(ContextTransfer.builder()
                        .summary("Help with coding")
                        .preserveHistory(true)
                        .build())
                .build();

        AgentMessage message = AgentMessage.builder()
                .type(MessageType.ROUTE_REQUEST)
                .payload(route)
                .build();

        assertEquals(MessageType.ROUTE_REQUEST, message.getType());
        assertInstanceOf(RoutePayload.class, message.getPayload());
    }

    @Test
    void shouldCreateSurfaceUpdateMessage() {
        UiComponent component = UiComponent.builder()
                .id("btn-1")
                .type(ComponentType.BUTTON)
                .props(Map.of("label", "Submit"))
                .actions(Map.of("click", ActionConfig.builder()
                        .actionType("submit")
                        .params(Map.of("target", "form-1"))
                        .build()))
                .build();

        SurfaceUpdatePayload surface = SurfaceUpdatePayload.builder()
                .components(List.of(component))
                .build();

        AgentMessage message = AgentMessage.builder()
                .type(MessageType.SURFACE_UPDATE)
                .payload(surface)
                .build();

        assertEquals(MessageType.SURFACE_UPDATE, message.getType());
    }

    @Test
    void shouldCreateUserActionRequestMessage() {
        FormConfig form = FormConfig.builder()
                .title("Login Required")
                .fields(List.of(
                        FormField.builder()
                                .name("username")
                                .type("text")
                                .label("Username")
                                .required(true)
                                .build(),
                        FormField.builder()
                                .name("password")
                                .type("password")
                                .label("Password")
                                .required(true)
                                .build()
                ))
                .submitText("Login")
                .cancelText("Cancel")
                .build();

        UserActionRequestPayload action = UserActionRequestPayload.builder()
                .actionType(ActionType.FORM_SUBMIT)
                .form(form)
                .build();

        AgentMessage message = AgentMessage.builder()
                .type(MessageType.USER_ACTION_REQUEST)
                .payload(action)
                .build();

        assertEquals(MessageType.USER_ACTION_REQUEST, message.getType());
    }

    @Test
    void shouldSupportConfirmationAction() {
        ConfirmationConfig confirm = ConfirmationConfig.builder()
                .title("Delete File")
                .message("Are you sure you want to delete this file?")
                .confirmText("Delete")
                .cancelText("Cancel")
                .severity("danger")
                .build();

        UserActionRequestPayload action = UserActionRequestPayload.builder()
                .actionType(ActionType.CONFIRM)
                .confirmation(confirm)
                .build();

        assertEquals("danger", action.getConfirmation().getSeverity());
    }

    @Test
    void shouldSupportSelectionAction() {
        SelectionConfig selection = SelectionConfig.builder()
                .title("Select Programming Language")
                .message("Choose your preferred language")
                .allowMultiple(false)
                .options(List.of(
                        Option.builder().value("java").label("Java").build(),
                        Option.builder().value("python").label("Python").build(),
                        Option.builder().value("typescript").label("TypeScript").build()
                ))
                .build();

        UserActionRequestPayload action = UserActionRequestPayload.builder()
                .actionType(ActionType.SINGLE_SELECT)
                .selection(selection)
                .build();

        assertEquals(3, action.getSelection().getOptions().size());
    }

    @Test
    void routePayload_shouldSupportCallbackConfig() {
        RoutePayload route = RoutePayload.builder()
                .action(RouteAction.DELEGATE)
                .callback(CallbackConfig.builder()
                        .notifyOnComplete(true)
                        .autoReturn(true)
                        .build())
                .build();

        assertTrue(route.getCallback().isNotifyOnComplete());
        assertTrue(route.getCallback().isAutoReturn());
    }

    @Test
    void routePayload_shouldSupportUiHint() {
        RoutePayload route = RoutePayload.builder()
                .action(RouteAction.DELEGATE)
                .uiHint(UiHint.builder()
                        .requireConfirmation(true)
                        .confirmationMessage("Switch to new agent?")
                        .loadingMessage("Connecting...")
                        .build())
                .build();

        assertTrue(route.getUiHint().isRequireConfirmation());
        assertEquals("Connecting...", route.getUiHint().getLoadingMessage());
    }

    @Test
    void contextTransfer_shouldSupportVariables() {
        ContextTransfer context = ContextTransfer.builder()
                .summary("Task summary")
                .variables(Map.of(
                        "language", "java",
                        "framework", "spring"
                ))
                .preserveHistory(true)
                .build();

        assertEquals("java", context.getVariables().get("language"));
    }
}
