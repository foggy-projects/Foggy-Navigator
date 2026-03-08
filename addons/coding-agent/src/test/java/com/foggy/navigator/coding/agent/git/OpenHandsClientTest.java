package com.foggy.navigator.coding.agent.git;

import com.foggy.navigator.coding.agent.api.model.CreateConversationRequest;
import com.foggy.navigator.coding.agent.git.model.OpenHandsConversationResponse;
import com.foggy.navigator.coding.agent.git.model.OpenHandsMessageResponse;
import com.foggy.navigator.coding.agent.git.model.OpenHandsEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.*;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OpenHandsClientTest {

    @Mock
    private RestTemplate restTemplate;

    private OpenHandsClient openHandsClient;

    private static final String TEST_API_URL = "http://localhost:8080";
    private static final String TEST_API_KEY = "test-api-key";

    @BeforeEach
    void setUp() {
        openHandsClient = new OpenHandsClient(restTemplate, TEST_API_URL, TEST_API_KEY);
    }

    @Test
    void testCreateConversation_Success() {
        CreateConversationRequest request = CreateConversationRequest.builder()
                .userId("user-123")
                .projectId("project-A")
                .gitRepoUrl("https://github.com/org/semantic-layer.git")
                .branchName("main")
                .build();

        OpenHandsConversationResponse response = OpenHandsConversationResponse.builder()
                .conversation_id("conv-123")
                .status("READY")
                .build();

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(OpenHandsConversationResponse.class)
        )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        OpenHandsConversationResponse result = openHandsClient.createConversation(request);

        assertNotNull(result);
        assertEquals("conv-123", result.getConversation_id());
        assertEquals("READY", result.getStatus());

        verify(restTemplate).postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(OpenHandsConversationResponse.class)
        );
    }

    @Test
    void testGetConversation_Success() {
        String conversationId = "conv-123";
        OpenHandsConversationResponse response = OpenHandsConversationResponse.builder()
                .conversation_id(conversationId)
                .status("READY")
                .build();

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(OpenHandsConversationResponse.class)
        )).thenReturn(new ResponseEntity<>(response, HttpStatus.OK));

        OpenHandsConversationResponse result = openHandsClient.getConversation(conversationId);

        assertNotNull(result);
        assertEquals(conversationId, result.getConversation_id());
    }

    @Test
    void testDeleteConversation_Success() {
        String conversationId = "conv-123";

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(Void.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.NO_CONTENT));

        openHandsClient.deleteConversation(conversationId);

        verify(restTemplate).exchange(
                anyString(),
                eq(HttpMethod.DELETE),
                any(HttpEntity.class),
                eq(Void.class)
        );
    }

    @Test
    void testStopConversation_Success() {
        String conversationId = "conv-123";

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(Void.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.OK));

        openHandsClient.stopConversation(conversationId);

        verify(restTemplate).postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(Void.class)
        );
    }

    @Test
    void testSendMessage_NoConversationUrl_ThrowsException() {
        String conversationId = "conv-123";
        String content = "Hello";

        // getConversationInfo returns empty list → no conversation_url → should throw
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)
        )).thenReturn(new ResponseEntity<>(List.of(), HttpStatus.OK));

        assertThrows(RuntimeException.class, () ->
                openHandsClient.sendMessage(conversationId, content)
        );
    }

    @Test
    void testSearchEvents_Success() {
        String conversationId = "conv-123";
        OpenHandsEvent event1 = OpenHandsEvent.builder()
                .id("event-1")
                .kind("MESSAGE_SENT")
                .build();

        OpenHandsEvent event2 = OpenHandsEvent.builder()
                .id("event-2")
                .kind("CONVERSATION_STATUS")
                .build();

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)
        )).thenReturn(new ResponseEntity<>(List.of(event1, event2), HttpStatus.OK));

        List<OpenHandsEvent> result = openHandsClient.searchEvents(
                conversationId,
                null,
                null,
                null,
                null,
                100
        );

        assertNotNull(result);
        assertEquals(2, result.size());
        assertEquals("event-1", result.get(0).getId());
        assertEquals("event-2", result.get(1).getId());
    }

    @Test
    void testGetNewEvents_NoConversationUrl_ReturnsEmpty() {
        String conversationId = "conv-123";

        // getConversationInfo returns empty list → no conversation_url
        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)
        )).thenReturn(new ResponseEntity<>(List.of(), HttpStatus.OK));

        Map<String, Object> result = openHandsClient.getNewEvents(conversationId, null);

        assertNotNull(result);
        List<?> items = (List<?>) result.get("items");
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    void testPost_Success() {
        String path = "/test/endpoint";

        when(restTemplate.postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(Void.class)
        )).thenReturn(new ResponseEntity<>(HttpStatus.OK));

        openHandsClient.post(path, Map.of("key", "value"), Void.class);

        verify(restTemplate).postForEntity(
                anyString(),
                any(HttpEntity.class),
                eq(Void.class)
        );
    }

    @Test
    void testGet_Success() {
        String path = "/test/endpoint";
        String expectedResponse = "test response";

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)
        )).thenReturn(new ResponseEntity<>(expectedResponse, HttpStatus.OK));

        String result = openHandsClient.get(path, String.class);

        assertEquals(expectedResponse, result);
    }

    @Test
    void testConstructor_SetsCorrectValues() {
        String apiUrl = "http://test-api:8080";
        String apiKey = "test-key-123";

        OpenHandsClient client = new OpenHandsClient(restTemplate, apiUrl, apiKey);

        assertNotNull(client);
    }

    @Test
    void testSearchEvents_WithFilters() {
        String conversationId = "conv-123";

        when(restTemplate.exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)
        )).thenReturn(new ResponseEntity<>(List.of(), HttpStatus.OK));

        openHandsClient.searchEvents(
                conversationId,
                "MESSAGE_SENT",
                "2024-01-01T00:00:00",
                "2024-12-31T23:59:59",
                "page-1",
                50
        );

        verify(restTemplate).exchange(
                anyString(),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)
        );
    }
}
