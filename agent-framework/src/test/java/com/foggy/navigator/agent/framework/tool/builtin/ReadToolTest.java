package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ReadTool 单元测试 — L1
 */
class ReadToolTest {

    private final ReadTool readTool = new ReadTool();

    // ---- 元数据 ----

    @Test
    void name() {
        assertEquals("Read", readTool.getName());
    }

    // ---- 参数校验 ----

    @Test
    void execute_nullFilePath_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of())
                .build();
        ToolExecutionResult result = readTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("INVALID_PARAM", result.getErrorCode());
    }

    @Test
    void execute_fileNotFound_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", "/nonexistent/file.txt"))
                .build();
        ToolExecutionResult result = readTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("FILE_NOT_FOUND", result.getErrorCode());
    }

    @Test
    void execute_directoryNotFile_error(@TempDir Path tempDir) {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", tempDir.toString()))
                .build();
        ToolExecutionResult result = readTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("NOT_A_FILE", result.getErrorCode());
    }

    // ---- 正常读取 ----

    @Test
    @SuppressWarnings("unchecked")
    void execute_readsFileContent(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", file.toString()))
                .build();
        ToolExecutionResult result = readTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(3, (int) data.get("totalLines"));
        assertEquals(3, (int) data.get("linesRead"));
        String content = (String) data.get("content");
        assertTrue(content.contains("line1"));
        assertTrue(content.contains("line2"));
        assertTrue(content.contains("line3"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withOffset(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", file.toString(), "offset", 3))
                .build();
        ToolExecutionResult result = readTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(3, (int) data.get("fromLine"));
        String content = (String) data.get("content");
        assertTrue(content.contains("line3"));
        assertFalse(content.contains("line1"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withLimit(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2\nline3\nline4\nline5\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", file.toString(), "limit", 2))
                .build();
        ToolExecutionResult result = readTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2, (int) data.get("linesRead"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_contentIncludesLineNumbers(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "alpha\nbeta\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", file.toString()))
                .build();
        ToolExecutionResult result = readTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        String content = (String) data.get("content");
        // 行号格式：6位 + →
        assertTrue(content.contains("1→alpha") || content.contains("1\talpha") || content.matches("(?s).*1.alpha.*"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_emptyFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("empty.txt");
        Files.writeString(file, "");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("file_path", file.toString()))
                .build();
        ToolExecutionResult result = readTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, (int) data.get("linesRead"));
    }
}
