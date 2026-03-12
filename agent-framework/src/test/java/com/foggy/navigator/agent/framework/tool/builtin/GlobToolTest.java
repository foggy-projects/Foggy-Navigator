package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GlobTool 单元测试 — L1
 */
class GlobToolTest {

    private final GlobTool globTool = new GlobTool();

    // ---- 元数据 ----

    @Test
    void name() {
        assertEquals("Glob", globTool.getName());
    }

    // ---- 参数校验 ----

    @Test
    void execute_nullPattern_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of())
                .build();
        ToolExecutionResult result = globTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("INVALID_PARAM", result.getErrorCode());
    }

    @Test
    void execute_nonExistentPath_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "*.txt", "path", "/nonexistent/dir/xyz"))
                .build();
        ToolExecutionResult result = globTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("PATH_NOT_FOUND", result.getErrorCode());
    }

    // ---- 文件匹配 ----

    @Test
    @SuppressWarnings("unchecked")
    void execute_matchesFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("hello.java"), "class Hello {}");
        Files.writeString(tempDir.resolve("world.java"), "class World {}");
        Files.writeString(tempDir.resolve("readme.txt"), "doc");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "*.java", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = globTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<String> files = (List<String>) data.get("files");
        assertEquals(2, files.size());
        assertTrue(files.stream().allMatch(f -> f.endsWith(".java")));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_noMatch_emptyResult(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("readme.txt"), "doc");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "*.java", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = globTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_nestedDirectories(@TempDir Path tempDir) throws Exception {
        Path subDir = tempDir.resolve("src");
        Files.createDirectories(subDir);
        Files.writeString(subDir.resolve("App.java"), "class App {}");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "**/*.java", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = globTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_skipsHiddenDirs(@TempDir Path tempDir) throws Exception {
        // .git is a hidden directory — should be skipped by GlobTool
        Path hiddenDir = tempDir.resolve(".git");
        Files.createDirectories(hiddenDir);
        Files.writeString(hiddenDir.resolve("config.java"), "hidden");

        // Put Main.java inside a visible subdirectory so **/*.java matches it
        Path srcDir = tempDir.resolve("src");
        Files.createDirectories(srcDir);
        Files.writeString(srcDir.resolve("Main.java"), "public class Main {}");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "**/*.java", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = globTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        List<String> files = (List<String>) data.get("files");
        assertEquals(1, files.size());
        assertTrue(files.get(0).contains("Main.java"));
    }
}
