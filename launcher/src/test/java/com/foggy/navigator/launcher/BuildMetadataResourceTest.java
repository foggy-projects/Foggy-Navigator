package com.foggy.navigator.launcher;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BuildMetadataResourceTest {

    @Test
    void packagedMetadataContainsOnlySafeBuildAndRevisionFields() throws IOException {
        Properties build = load("META-INF/build-info.properties");
        Properties git = load("git.properties");

        assertFalse(build.getProperty("build.version", "").isBlank());
        assertFalse(build.getProperty("build.time", "").isBlank());
        String fullCommit = git.getProperty("git.commit.id.full", "");
        String dirty = git.getProperty("git.dirty", "");
        assertTrue(fullCommit.matches("[0-9a-fA-F]{40}"));
        assertFalse(git.getProperty("git.commit.id.abbrev", "").isBlank());
        assertFalse(git.getProperty("git.commit.time", "").isBlank());
        assertTrue("true".equalsIgnoreCase(dirty) || "false".equalsIgnoreCase(dirty));
        assertTrue(git.stringPropertyNames().stream()
                .noneMatch(key -> key.contains("user") || key.contains("email") || key.contains("message")));

        Path repository = repositoryRoot();
        assertEquals(git(repository, "rev-parse", "HEAD").toLowerCase(), fullCommit.toLowerCase(),
                "generated git.properties must describe the repository being built");
        boolean repositoryDirty = !git(repository, "status", "--porcelain", "--untracked-files=no").isBlank();
        assertEquals(Boolean.toString(repositoryDirty), dirty.toLowerCase(),
                "generated git.properties must describe the candidate's tracked worktree state");

        String expectedCommit = System.getProperty("navigator.expectedCommit", "").trim();
        if (!expectedCommit.isEmpty()) {
            assertEquals(expectedCommit.toLowerCase(), fullCommit.toLowerCase());
        }
        if (Boolean.getBoolean("navigator.requireCleanBuild")) {
            assertEquals("false", dirty.toLowerCase());
        }
    }

    private Properties load(String resource) throws IOException {
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertNotNull(input, () -> "missing generated build metadata resource: " + resource);
            Properties properties = new Properties();
            properties.load(input);
            return properties;
        }
    }

    private Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git")) && Files.isRegularFile(current.resolve("pom.xml"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("repository root is unavailable");
    }

    private String git(Path repository, String... arguments) throws IOException {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(repository.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            assertEquals(0, process.waitFor(), () -> "git command failed: " + output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while reading repository provenance", e);
        }
        return output;
    }
}
