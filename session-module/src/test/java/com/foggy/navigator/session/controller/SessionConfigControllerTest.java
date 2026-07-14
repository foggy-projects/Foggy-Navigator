package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.common.entity.SessionEntity;
import com.foggy.navigator.session.dto.SessionConfigDTO;
import com.foggy.navigator.session.service.SessionMetadataService;
import com.foggy.navigator.session.service.SessionTaskResourceAccessService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionConfigControllerTest {

    private static final String USER_ID = "user-1";
    private static final String TENANT_ID = "tenant-1";

    @Mock
    private SessionMetadataService sessionMetadataService;

    @Mock
    private SessionTaskResourceAccessService resourceAccessService;

    private SessionConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionConfigController(sessionMetadataService, resourceAccessService);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
                .tenantId(TENANT_ID)
                .build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void listConfigs_missingSessionIds_returnsEmptyList() {
        RX<List<SessionConfigDTO>> result = controller.listConfigs(null);

        assertNotNull(result.getData());
        assertEquals(List.of(), result.getData());
        verifyNoInteractions(sessionMetadataService, resourceAccessService);
    }

    @Test
    void listConfigs_blankSessionIds_returnsEmptyList() {
        RX<List<SessionConfigDTO>> result = controller.listConfigs("  ");

        assertNotNull(result.getData());
        assertEquals(List.of(), result.getData());
        verifyNoInteractions(sessionMetadataService, resourceAccessService);
    }

    @Test
    void listConfigs_splitsAndTrimsSessionIds() {
        List<SessionConfigDTO> configs = List.of(SessionConfigDTO.builder()
                .sessionId("session-1")
                .build());
        stubOwned("session-1");
        stubOwned("session-2");
        when(sessionMetadataService.listBySessionIds(USER_ID, List.of("session-1", "session-2")))
                .thenReturn(configs);

        RX<List<SessionConfigDTO>> result = controller.listConfigs(" session-1, ,session-2 ");

        assertEquals(configs, result.getData());
        InOrder order = inOrder(resourceAccessService, sessionMetadataService);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(resourceAccessService).requireOwnedSession("session-2", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).listBySessionIds(USER_ID, List.of("session-1", "session-2"));
    }

    @Test
    void listConfigsByPost_usesSessionIdsFromBody() {
        List<SessionConfigDTO> configs = List.of(SessionConfigDTO.builder()
                .sessionId("session-1")
                .build());
        SessionConfigController.ListConfigsForm form = new SessionConfigController.ListConfigsForm();
        form.setSessionIds(List.of(" session-1 ", "", "session-2"));
        stubOwned("session-1");
        stubOwned("session-2");
        when(sessionMetadataService.listBySessionIds(USER_ID, List.of("session-1", "session-2")))
                .thenReturn(configs);

        RX<List<SessionConfigDTO>> result = controller.listConfigsByPost(form);

        assertEquals(configs, result.getData());
        InOrder order = inOrder(resourceAccessService, sessionMetadataService);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(resourceAccessService).requireOwnedSession("session-2", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).listBySessionIds(USER_ID, List.of("session-1", "session-2"));
    }

    @Test
    void listConfigsByPost_missingBody_returnsEmptyList() {
        RX<List<SessionConfigDTO>> result = controller.listConfigsByPost(null);

        assertNotNull(result.getData());
        assertEquals(List.of(), result.getData());
        verifyNoInteractions(sessionMetadataService, resourceAccessService);
    }

    @Test
    void listConfigs_whenAnySessionIsUnauthorized_doesNotReadMetadata() {
        stubOwned("session-1");
        when(resourceAccessService.requireOwnedSession("session-2", USER_ID, TENANT_ID))
                .thenThrow(denied());

        assertThrows(SecurityException.class,
                () -> controller.listConfigs("session-1,session-2"));

        verifyNoInteractions(sessionMetadataService);
    }

    @Test
    void singleSessionConfigAndStateOperations_authorizeBeforeDelegating() {
        SessionConfigDTO config = SessionConfigDTO.builder().sessionId("session-1").build();
        stubOwned("session-1");
        when(sessionMetadataService.updateTags("session-1", USER_ID, List.of("tag-1"))).thenReturn(config);
        when(sessionMetadataService.updatePin("session-1", USER_ID, true)).thenReturn(config);
        when(sessionMetadataService.updateTitle("session-1", USER_ID, "Title")).thenReturn(config);
        when(sessionMetadataService.updateMilestone("session-1", USER_ID, "milestone-1")).thenReturn(config);
        when(sessionMetadataService.bindAuth(
                "session-1", USER_ID, "API_KEY", "secret", "https://example.test", "model-1"))
                .thenReturn(config);
        when(sessionMetadataService.updateAuth(
                "session-1", USER_ID, "API_KEY", "secret", "https://example.test", "model-1"))
                .thenReturn(config);
        when(sessionMetadataService.archiveConversation("session-1", USER_ID)).thenReturn(config);
        when(sessionMetadataService.unarchiveConversation("session-1", USER_ID)).thenReturn(config);
        when(sessionMetadataService.holdConversation("session-1", USER_ID)).thenReturn(config);
        when(sessionMetadataService.unholdConversation("session-1", USER_ID)).thenReturn(config);

        SessionConfigController.UpdateTagsForm tags = new SessionConfigController.UpdateTagsForm();
        tags.setTags(List.of("tag-1"));
        SessionConfigController.UpdatePinForm pin = new SessionConfigController.UpdatePinForm();
        pin.setPinned(true);
        SessionConfigController.UpdateTitleForm title = new SessionConfigController.UpdateTitleForm();
        title.setTitle("Title");
        SessionConfigController.UpdateMilestoneForm milestone = new SessionConfigController.UpdateMilestoneForm();
        milestone.setMilestoneId("milestone-1");
        SessionConfigController.UpdateAuthForm auth = new SessionConfigController.UpdateAuthForm();
        auth.setAuthMode("API_KEY");
        auth.setAuthToken("secret");
        auth.setBaseUrl("https://example.test");
        auth.setModelConfigId("model-1");

        controller.updateTags("session-1", tags);
        controller.updatePin("session-1", pin);
        controller.updateTitle("session-1", title);
        controller.updateMilestone("session-1", milestone);
        controller.bindAuth("session-1", auth);
        controller.updateAuth("session-1", auth);
        controller.archiveConversation("session-1");
        controller.unarchiveConversation("session-1");
        controller.holdConversation("session-1");
        controller.unholdConversation("session-1");

        InOrder order = inOrder(resourceAccessService, sessionMetadataService);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).updateTags("session-1", USER_ID, List.of("tag-1"));
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).updatePin("session-1", USER_ID, true);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).updateTitle("session-1", USER_ID, "Title");
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).updateMilestone("session-1", USER_ID, "milestone-1");
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).bindAuth(
                "session-1", USER_ID, "API_KEY", "secret", "https://example.test", "model-1");
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).updateAuth(
                "session-1", USER_ID, "API_KEY", "secret", "https://example.test", "model-1");
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).archiveConversation("session-1", USER_ID);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).unarchiveConversation("session-1", USER_ID);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).holdConversation("session-1", USER_ID);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).unholdConversation("session-1", USER_ID);
    }

    @Test
    void singleSessionMutation_whenUnauthorized_doesNotMutateMetadata() {
        SessionConfigController.UpdateTitleForm form = new SessionConfigController.UpdateTitleForm();
        form.setTitle("Title");
        when(resourceAccessService.requireOwnedSession("session-1", USER_ID, TENANT_ID))
                .thenThrow(denied());

        assertThrows(SecurityException.class,
                () -> controller.updateTitle("session-1", form));

        verifyNoInteractions(sessionMetadataService);
    }

    @Test
    void batchBindAuth_validatesEverySessionBeforeMutation() {
        SessionConfigController.BatchBindAuthForm form = batchForm();
        stubOwned("session-1");
        stubOwned("session-2");
        when(sessionMetadataService.batchBindAuth(
                List.of("session-1", "session-2"), USER_ID, "API_KEY", "secret",
                "https://example.test", true, "model-1"))
                .thenReturn(2);

        RX<java.util.Map<String, Object>> result = controller.batchBindAuth(form);

        assertEquals(2, result.getData().get("bound"));
        assertEquals(2, result.getData().get("total"));
        InOrder order = inOrder(resourceAccessService, sessionMetadataService);
        order.verify(resourceAccessService).requireOwnedSession("session-1", USER_ID, TENANT_ID);
        order.verify(resourceAccessService).requireOwnedSession("session-2", USER_ID, TENANT_ID);
        order.verify(sessionMetadataService).batchBindAuth(
                List.of("session-1", "session-2"), USER_ID, "API_KEY", "secret",
                "https://example.test", true, "model-1");
    }

    @Test
    void batchBindAuth_whenAnySessionIsUnauthorized_isAtomicFailClosed() {
        SessionConfigController.BatchBindAuthForm form = batchForm();
        stubOwned("session-1");
        when(resourceAccessService.requireOwnedSession("session-2", USER_ID, TENANT_ID))
                .thenThrow(denied());

        assertThrows(SecurityException.class, () -> controller.batchBindAuth(form));

        verifyNoInteractions(sessionMetadataService);
    }

    private void stubOwned(String sessionId) {
        SessionEntity session = new SessionEntity();
        session.setId(sessionId);
        session.setUserId(USER_ID);
        session.setTenantId(TENANT_ID);
        when(resourceAccessService.requireOwnedSession(sessionId, USER_ID, TENANT_ID))
                .thenReturn(session);
    }

    private static SessionConfigController.BatchBindAuthForm batchForm() {
        SessionConfigController.BatchBindAuthForm form = new SessionConfigController.BatchBindAuthForm();
        form.setSessionIds(List.of("session-1", "session-2"));
        form.setAuthMode("API_KEY");
        form.setAuthToken("secret");
        form.setBaseUrl("https://example.test");
        form.setSkipExisting(true);
        form.setModelConfigId("model-1");
        return form;
    }

    private static SecurityException denied() {
        return new SecurityException("Resource access denied");
    }
}
