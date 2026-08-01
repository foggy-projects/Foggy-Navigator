package com.foggy.navigator.session.lifecycle;

import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class FileLifecycleActivationArtifactSource
        implements LifecycleActivationArtifactSource {
    private final LifecycleActivationProperties properties;
    private final ObjectMapper objectMapper;

    public FileLifecycleActivationArtifactSource(
            LifecycleActivationProperties properties,
            ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper.copy()
                .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    }

    @Override
    public ActivationArtifacts load() {
        Path manifestPath = requiredPath(properties.getManifestPath());
        Path observationPath = requiredPath(properties.getObservationPath());
        try {
            byte[] manifestBytes = readTrustedFile(manifestPath);
            LifecycleActivationManifest manifest = objectMapper.readValue(
                    manifestBytes, LifecycleActivationManifest.class);
            requireTargetOwned(manifestPath, manifest);
            requireTargetOwned(observationPath, manifest);
            if (manifest.target().observationFile() == null
                    || !observationPath.equals(Path.of(
                    manifest.target().observationFile())
                    .toAbsolutePath().normalize())) {
                throw denied(LifecycleActivationReason.MANIFEST_MISMATCH);
            }
            LifecycleActivationManifest.ControllerObservation observation =
                    objectMapper.readValue(
                            readTrustedFile(observationPath),
                            LifecycleActivationManifest.ControllerObservation.class);
            String digest = sha256(objectMapper.writeValueAsBytes(
                    objectMapper.readTree(manifestBytes)));
            if (!digest.equals(observation.manifestDigest())) {
                throw denied(LifecycleActivationReason.MANIFEST_MISMATCH);
            }
            return new ActivationArtifacts(manifest, digest, observation);
        } catch (LifecycleActivationDeniedException denied) {
            throw denied;
        } catch (Exception unavailable) {
            throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
        }
    }

    public String controllerInventoryDigest(
            List<LifecycleActivationManifest.Controller> controllers) {
        try {
            List<Map<String, Object>> normalized = new ArrayList<>();
            for (LifecycleActivationManifest.Controller controller : controllers) {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("kind", controller.kind());
                value.put("id", controller.id());
                value.put("state", controller.state());
                value.put("restartPolicy", controller.restartPolicy());
                value.put("ownershipRunId", controller.ownershipRunId());
                value.put("source", controller.source());
                value.put("artifactCommit", controller.artifactCommit());
                value.put("cwd", controller.cwd());
                normalized.add(value);
            }
            normalized.sort(Comparator
                    .comparing((Map<String, Object> value) ->
                            String.valueOf(value.get("kind")))
                    .thenComparing(value -> String.valueOf(value.get("id"))));
            return sha256(objectMapper.writeValueAsBytes(normalized));
        } catch (Exception invalid) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
    }

    private Path requiredPath(String value) {
        if (value == null) {
            throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(path)
                || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
        }
        return path;
    }

    private byte[] readTrustedFile(Path path) throws Exception {
        try {
            var permissions = Files.getPosixFilePermissions(
                    path, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
            }
        } catch (UnsupportedOperationException ignored) {
            // Windows ACL enforcement remains target-launcher owned. Symlinks
            // and exact target-root containment are still checked here.
        }
        return Files.readAllBytes(path);
    }

    private void requireTargetOwned(
            Path path, LifecycleActivationManifest manifest) {
        if (manifest == null || manifest.target() == null
                || manifest.target().root() == null) {
            throw denied(LifecycleActivationReason.MANIFEST_INVALID);
        }
        Path root = Path.of(manifest.target().root())
                .toAbsolutePath().normalize();
        try {
            Path realRoot = root.toRealPath();
            Path realPath = path.toRealPath();
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !root.equals(realRoot)
                    || !path.equals(realPath)
                    || !realPath.startsWith(realRoot)) {
                throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
            }
            var permissions = Files.getPosixFilePermissions(
                    root, LinkOption.NOFOLLOW_LINKS);
            if (permissions.contains(PosixFilePermission.GROUP_WRITE)
                    || permissions.contains(PosixFilePermission.OTHERS_WRITE)) {
                throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
            }
        } catch (LifecycleActivationDeniedException denied) {
            throw denied;
        } catch (UnsupportedOperationException ignored) {
            if (Files.isSymbolicLink(root)
                    || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                    || !path.startsWith(root)) {
                throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
            }
        } catch (Exception unavailable) {
            throw denied(LifecycleActivationReason.MANIFEST_UNAVAILABLE);
        }
    }

    private String sha256(byte[] value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private LifecycleActivationDeniedException denied(String reason) {
        return new LifecycleActivationDeniedException(reason);
    }
}
