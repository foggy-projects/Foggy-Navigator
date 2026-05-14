package com.foggy.navigator.session.controller;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.common.dto.CurrentUser;
import com.foggy.navigator.session.dto.SessionConfigDTO;
import com.foggy.navigator.session.service.SessionMetadataService;
import com.foggyframework.core.ex.RX;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionConfigControllerTest {

    private static final String USER_ID = "user-1";

    @Mock
    private SessionMetadataService sessionMetadataService;

    private SessionConfigController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionConfigController(sessionMetadataService);
        UserContext.setCurrentUser(CurrentUser.builder()
                .userId(USER_ID)
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
        verifyNoInteractions(sessionMetadataService);
    }

    @Test
    void listConfigs_blankSessionIds_returnsEmptyList() {
        RX<List<SessionConfigDTO>> result = controller.listConfigs("  ");

        assertNotNull(result.getData());
        assertEquals(List.of(), result.getData());
        verifyNoInteractions(sessionMetadataService);
    }

    @Test
    void listConfigs_splitsAndTrimsSessionIds() {
        List<SessionConfigDTO> configs = List.of(SessionConfigDTO.builder()
                .sessionId("session-1")
                .build());
        when(sessionMetadataService.listBySessionIds(USER_ID, List.of("session-1", "session-2")))
                .thenReturn(configs);

        RX<List<SessionConfigDTO>> result = controller.listConfigs(" session-1, ,session-2 ");

        assertEquals(configs, result.getData());
        verify(sessionMetadataService).listBySessionIds(USER_ID, List.of("session-1", "session-2"));
    }

    @Test
    void listConfigsByPost_usesSessionIdsFromBody() {
        List<SessionConfigDTO> configs = List.of(SessionConfigDTO.builder()
                .sessionId("session-1")
                .build());
        SessionConfigController.ListConfigsForm form = new SessionConfigController.ListConfigsForm();
        form.setSessionIds(List.of(" session-1 ", "", "session-2"));
        when(sessionMetadataService.listBySessionIds(USER_ID, List.of("session-1", "session-2")))
                .thenReturn(configs);

        RX<List<SessionConfigDTO>> result = controller.listConfigsByPost(form);

        assertEquals(configs, result.getData());
        verify(sessionMetadataService).listBySessionIds(USER_ID, List.of("session-1", "session-2"));
    }

    @Test
    void listConfigsByPost_missingBody_returnsEmptyList() {
        RX<List<SessionConfigDTO>> result = controller.listConfigsByPost(null);

        assertNotNull(result.getData());
        assertEquals(List.of(), result.getData());
        verifyNoInteractions(sessionMetadataService);
    }
}
