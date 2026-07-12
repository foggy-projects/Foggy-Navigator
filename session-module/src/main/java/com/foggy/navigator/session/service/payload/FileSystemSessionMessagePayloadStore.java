package com.foggy.navigator.session.service.payload;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import org.springframework.stereotype.Service;

/**
 * Persistent local-filesystem implementation. It deliberately derives keys from
 * a message id rather than accepting a caller supplied path, and always verifies
 * the original SHA-256 when reading an existing object.
 */
@Service
public class FileSystemSessionMessagePayloadStore implements SessionMessagePayloadStore {

    public static final String BACKEND = "filesystem";
    public static final String CONTENT_ENCODING = "gzip";

    private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile("[a-f0-9]{64}\\.gz");
    private static final int PUBLISH_LOCK_STRIPES = 64;
    private static final Object[] PUBLISH_LOCKS = createPublishLocks();
    private static final String LOCK_DIRECTORY = ".session-message-payload-locks";

    private final SessionMessagePayloadProperties properties;

    public FileSystemSessionMessagePayloadStore(SessionMessagePayloadProperties properties) {
        this.properties = properties;
    }

    @Override
    public String backend() {
        return BACKEND;
    }

    @Override
    public StoredSessionMessagePayload write(SessionMessagePayload payload) {
        Path root = requireReadyDirectory();
        byte[] original = payload.content();
        if (original.length > properties.getMaxPayloadBytes()) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_TOO_LARGE",
                "Session message payload exceeds configured maxPayloadBytes"
            );
        }

        String sha256 = sha256(original);
        String storageKey = sha256(payload.messageId().getBytes(StandardCharsets.UTF_8)) + ".gz";
        Path target = resolveStoragePath(root, storageKey);

        byte[] compressed = gzip(original);
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".session-message-payload-", ".tmp");
            Files.write(temporary, compressed);
            synchronized (publishLock(storageKey)) {
                Path lockFile = lockFile(root, storageKey);
                try (FileChannel channel = FileChannel.open(lockFile,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    if (Files.exists(target)) {
                        // A concurrent replay already published this stable
                        // key. Only accept it after checking the immutable
                        // original SHA; divergent bytes fail instead of
                        // changing the first writer's payload.
                        byte[] existing = read(storageKey, CONTENT_ENCODING, sha256);
                        return new StoredSessionMessagePayload(
                            BACKEND, storageKey, CONTENT_ENCODING, existing.length, fileSize(target), sha256
                        );
                    }
                    try {
                        // JDK permits implementation-specific replacement
                        // when ATOMIC_MOVE targets an existing file. The
                        // intra-JVM stripe plus cooperative cross-JVM file
                        // lock makes this a first-write-only move for every
                        // FileSystemSessionMessagePayloadStore writer.
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException e) {
                        throw new SessionMessagePayloadStoreException(
                            "SESSION_MESSAGE_PAYLOAD_STORE_ATOMIC_MOVE_UNSUPPORTED",
                            "Session message payload store does not support atomic replacement", e
                        );
                    }
                }
                temporary = null;
                return new StoredSessionMessagePayload(
                    BACKEND, storageKey, CONTENT_ENCODING, original.length, fileSize(target), sha256
                );
            }
        } catch (SessionMessagePayloadStoreException e) {
            throw e;
        } catch (IOException e) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_WRITE_FAILED",
                "Unable to persist session message payload", e
            );
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The durable write failed; do not hide its original cause for cleanup noise.
                }
            }
        }
    }

    @Override
    public byte[] read(String storageKey, String contentEncoding, String expectedSha256) {
        if (!CONTENT_ENCODING.equals(contentEncoding)) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_UNSUPPORTED_ENCODING",
                "Unsupported session message payload content encoding"
            );
        }
        if (expectedSha256 == null || !expectedSha256.matches("[a-f0-9]{64}")) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_INVALID_SHA256",
                "Expected session message payload SHA-256 is invalid"
            );
        }

        Path target = resolveStoragePath(requireReadyDirectory(), storageKey);
        if (!Files.isRegularFile(target) || !Files.isReadable(target)) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_READ_FAILED",
                "Session message payload object is unavailable for reading"
            );
        }

        try (InputStream source = Files.newInputStream(target);
             GZIPInputStream gzip = new GZIPInputStream(source);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            gzip.transferTo(output);
            byte[] original = output.toByteArray();
            if (!MessageDigest.isEqual(
                sha256(original).getBytes(StandardCharsets.US_ASCII),
                expectedSha256.getBytes(StandardCharsets.US_ASCII)
            )) {
                throw new SessionMessagePayloadStoreException(
                    "SESSION_MESSAGE_PAYLOAD_INTEGRITY_MISMATCH",
                    "Session message payload SHA-256 validation failed"
                );
            }
            return original;
        } catch (SessionMessagePayloadStoreException e) {
            throw e;
        } catch (IOException e) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_READ_FAILED",
                "Unable to read session message payload", e
            );
        }
    }

    @Override
    public PayloadStoreReadiness readiness() {
        try {
            requireReadyDirectory();
            return PayloadStoreReadiness.available();
        } catch (SessionMessagePayloadStoreException e) {
            return PayloadStoreReadiness.unavailable(e.getMessage());
        }
    }

    private Path requireReadyDirectory() {
        String configuredDirectory = properties.getFilesystem() == null
            ? null : properties.getFilesystem().getDirectory();
        if (configuredDirectory == null || configuredDirectory.isBlank()) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE",
                "Session message payload store directory is not configured"
            );
        }
        try {
            Path root = Path.of(configuredDirectory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (!Files.isDirectory(root) || !Files.isWritable(root)) {
                throw new SessionMessagePayloadStoreException(
                    "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE",
                    "Session message payload store directory is not writable"
                );
            }
            Path locks = root.resolve(LOCK_DIRECTORY).normalize();
            if (!locks.startsWith(root)) {
                throw new SessionMessagePayloadStoreException(
                    "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE",
                    "Session message payload store lock directory is invalid"
                );
            }
            Files.createDirectories(locks);
            if (!Files.isDirectory(locks) || !Files.isWritable(locks)) {
                throw new SessionMessagePayloadStoreException(
                    "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE",
                    "Session message payload store lock directory is not writable"
                );
            }
            return root;
        } catch (SessionMessagePayloadStoreException e) {
            throw e;
        } catch (IOException | SecurityException | IllegalArgumentException e) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_UNAVAILABLE",
                "Session message payload store directory cannot be prepared", e
            );
        }
    }

    private Path resolveStoragePath(Path root, String storageKey) {
        if (storageKey == null || !STORAGE_KEY_PATTERN.matcher(storageKey).matches()) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_INVALID_STORAGE_KEY",
                "Session message payload storage key is invalid"
            );
        }
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_INVALID_STORAGE_KEY",
                "Session message payload storage key escapes its root directory"
            );
        }
        return target;
    }

    private Object publishLock(String storageKey) {
        return PUBLISH_LOCKS[Math.floorMod(storageKey.hashCode(), PUBLISH_LOCKS.length)];
    }

    private Path lockFile(Path root, String storageKey) {
        int stripe = Math.floorMod(storageKey.hashCode(), PUBLISH_LOCK_STRIPES);
        return root.resolve(LOCK_DIRECTORY).resolve("stripe-" + stripe + ".lock").normalize();
    }

    private static Object[] createPublishLocks() {
        Object[] locks = new Object[PUBLISH_LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_READ_FAILED",
                "Unable to determine stored session message payload size", e
            );
        }
    }

    private byte[] gzip(byte[] original) {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             GZIPOutputStream gzip = new GZIPOutputStream(output)) {
            gzip.write(original);
            gzip.finish();
            return output.toByteArray();
        } catch (IOException e) {
            throw new SessionMessagePayloadStoreException(
                "SESSION_MESSAGE_PAYLOAD_STORE_WRITE_FAILED",
                "Unable to compress session message payload", e
            );
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available in the Java runtime", e);
        }
    }
}
