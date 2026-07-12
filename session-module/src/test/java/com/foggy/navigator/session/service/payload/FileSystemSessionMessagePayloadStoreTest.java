package com.foggy.navigator.session.service.payload;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSystemSessionMessagePayloadStoreTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void writeAndRead_roundTripsUtf8JsonAndUsesStableReplayKey() throws Exception {
        FileSystemSessionMessagePayloadStore store = storeAt(temporaryDirectory.resolve("payloads"));
        byte[] original = "{\"command\":\"echo \\\"你好 😀\\\\n\\\"\",\"result\":\"line\\n\\t\\\\\"}"
            .getBytes(StandardCharsets.UTF_8);
        SessionMessagePayload payload = new SessionMessagePayload(
            "codex-event:task-1:17", "session-1", "text/plain; charset=utf-8", original
        );

        StoredSessionMessagePayload first = store.write(payload);
        StoredSessionMessagePayload replay = store.write(payload);

        assertEquals(FileSystemSessionMessagePayloadStore.BACKEND, first.backend());
        assertEquals(FileSystemSessionMessagePayloadStore.CONTENT_ENCODING, first.contentEncoding());
        assertEquals(first.storageKey(), replay.storageKey());
        assertEquals(first.sha256(), replay.sha256());
        assertEquals(64, first.sha256().length());
        assertNotEquals("codex-event:task-1:17", first.storageKey());
        assertArrayEquals(original, store.read(first.storageKey(), first.contentEncoding(), first.sha256()));
        try (var paths = Files.list(temporaryDirectory.resolve("payloads"))) {
            assertEquals(1, paths.filter(Files::isRegularFile).count(),
                "an idempotent replay must not create another payload object");
        }
    }

    @Test
    void read_rejectsPathTraversalAndShaMismatch() {
        FileSystemSessionMessagePayloadStore store = storeAt(temporaryDirectory.resolve("payloads"));
        StoredSessionMessagePayload stored = store.write(new SessionMessagePayload(
            "message-1", "session-1", "text/plain; charset=utf-8", "payload".getBytes(StandardCharsets.UTF_8)
        ));

        SessionMessagePayloadStoreException traversal = assertThrows(SessionMessagePayloadStoreException.class,
            () -> store.read("../outside.gz", stored.contentEncoding(), stored.sha256()));
        assertEquals("SESSION_MESSAGE_PAYLOAD_INVALID_STORAGE_KEY", traversal.code());

        SessionMessagePayloadStoreException integrity = assertThrows(SessionMessagePayloadStoreException.class,
            () -> store.read(stored.storageKey(), stored.contentEncoding(), "0".repeat(64)));
        assertEquals("SESSION_MESSAGE_PAYLOAD_INTEGRITY_MISMATCH", integrity.code());
    }

    @Test
    void write_rejectsDifferentPayloadForAnExistingStableMessageId() {
        FileSystemSessionMessagePayloadStore store = storeAt(temporaryDirectory.resolve("payloads"));
        StoredSessionMessagePayload first = store.write(new SessionMessagePayload(
            "message-1", "session-1", "text/plain", "first".getBytes(StandardCharsets.UTF_8)
        ));

        SessionMessagePayloadStoreException conflict = assertThrows(SessionMessagePayloadStoreException.class,
            () -> store.write(new SessionMessagePayload(
                "message-1", "session-1", "text/plain", "different".getBytes(StandardCharsets.UTF_8)
            )));

        assertEquals("SESSION_MESSAGE_PAYLOAD_INTEGRITY_MISMATCH", conflict.code());
        assertArrayEquals("first".getBytes(StandardCharsets.UTF_8),
            store.read(first.storageKey(), first.contentEncoding(), first.sha256()));
    }

    @Test
    void concurrentWritesWithDivergentBytesPreserveOnlyTheFirstPublishedPayload() throws Exception {
        FileSystemSessionMessagePayloadStore store = storeAt(temporaryDirectory.resolve("payloads"));
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<StoredSessionMessagePayload> first = executor.submit(() -> {
                start.await();
                return store.write(new SessionMessagePayload(
                    "message-race", "session-1", "text/plain", "first".getBytes(StandardCharsets.UTF_8)
                ));
            });
            Future<StoredSessionMessagePayload> second = executor.submit(() -> {
                start.await();
                return store.write(new SessionMessagePayload(
                    "message-race", "session-1", "text/plain", "second".getBytes(StandardCharsets.UTF_8)
                ));
            });

            StoredSessionMessagePayload winner = completedResult(first, second);
            String published = readText(store, winner);
            assertTrue("first".equals(published) || "second".equals(published));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void readiness_reportsExplicitFailureWhenDirectoryCannotBePrepared() throws Exception {
        Path nonDirectory = Files.createFile(temporaryDirectory.resolve("not-a-directory"));
        FileSystemSessionMessagePayloadStore store = storeAt(nonDirectory);

        PayloadStoreReadiness readiness = store.readiness();

        assertFalse(readiness.ready());
        assertEquals("SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE", readiness.code());
        SessionMessagePayloadStoreException error = assertThrows(SessionMessagePayloadStoreException.class,
            () -> store.write(new SessionMessagePayload(
                "message-1", "session-1", "text/plain", "payload".getBytes(StandardCharsets.UTF_8)
            )));
        assertEquals("SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE", error.code());
        assertTrue(error.getMessage().contains("directory"));
    }

    @Test
    void readiness_reportsExplicitFailureForAnInvalidConfiguredPath() {
        SessionMessagePayloadProperties properties = new SessionMessagePayloadProperties();
        properties.getFilesystem().setDirectory("invalid\u0000payload-directory");
        FileSystemSessionMessagePayloadStore store = new FileSystemSessionMessagePayloadStore(properties);

        PayloadStoreReadiness readiness = store.readiness();

        assertFalse(readiness.ready());
        assertEquals("SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE", readiness.code());
    }

    private FileSystemSessionMessagePayloadStore storeAt(Path directory) {
        SessionMessagePayloadProperties properties = new SessionMessagePayloadProperties();
        properties.getFilesystem().setDirectory(directory.toString());
        return new FileSystemSessionMessagePayloadStore(properties);
    }

    private StoredSessionMessagePayload completedResult(Future<StoredSessionMessagePayload> first,
                                                         Future<StoredSessionMessagePayload> second)
            throws Exception {
        try {
            StoredSessionMessagePayload winner = first.get();
            assertConflict(second);
            return winner;
        } catch (ExecutionException firstFailure) {
            assertConflict(firstFailure);
            return second.get();
        }
    }

    private void assertConflict(Future<StoredSessionMessagePayload> future) throws Exception {
        assertConflict(assertThrows(ExecutionException.class, future::get));
    }

    private void assertConflict(ExecutionException failure) {
        assertTrue(failure.getCause() instanceof SessionMessagePayloadStoreException);
        assertEquals("SESSION_MESSAGE_PAYLOAD_INTEGRITY_MISMATCH",
            ((SessionMessagePayloadStoreException) failure.getCause()).code());
    }

    private String readText(FileSystemSessionMessagePayloadStore store, StoredSessionMessagePayload payload) {
        return new String(store.read(payload.storageKey(), payload.contentEncoding(), payload.sha256()),
            StandardCharsets.UTF_8);
    }
}
