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
 * GrepTool 单元测试 — L1
 */
class GrepToolTest {

    private final GrepTool grepTool = new GrepTool();

    // ---- 元数据 ----

    @Test
    void name() {
        assertEquals("Grep", grepTool.getName());
    }

    // ---- 参数校验 ----

    @Test
    void execute_nullPattern_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of())
                .build();
        ToolExecutionResult result = grepTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("INVALID_PARAM", result.getErrorCode());
    }

    @Test
    void execute_invalidRegex_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "[invalid"))
                .build();
        ToolExecutionResult result = grepTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("INVALID_PATTERN", result.getErrorCode());
    }

    @Test
    void execute_nonExistentPath_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "foo", "path", "/nonexistent/dir"))
                .build();
        ToolExecutionResult result = grepTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("PATH_NOT_FOUND", result.getErrorCode());
    }

    // ---- 内容搜索 ----

    @Test
    @SuppressWarnings("unchecked")
    void execute_findsPattern(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("Hello.java"),
                "public class Hello {\n    void sayHello() {}\n}\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "sayHello", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_caseInsensitive(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("app.txt"), "Hello World\nhello world\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "HELLO", "path", tempDir.toString(),
                        "case_insensitive", true))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_noMatch(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("data.txt"), "alpha beta gamma\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "delta", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(0, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_regexPattern(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("code.java"),
                "int count = 10;\nString name = \"alice\";\nint total = 42;\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "int \\w+ = \\d+", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(2, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_withGlobFilter(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("app.java"), "class App {}");
        Files.writeString(tempDir.resolve("doc.txt"), "class Doc");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "class", "path", tempDir.toString(),
                        "glob", "*.java"))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        // 只匹配 .java 文件
        assertEquals(1, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_singleFile(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("test.txt");
        Files.writeString(file, "line1\nline2 match\nline3\n");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "match", "path", file.toString()))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, (int) data.get("count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_skipsBinaryFiles(@TempDir Path tempDir) throws Exception {
        Files.writeString(tempDir.resolve("data.jar"), "some content with class in it");
        Files.writeString(tempDir.resolve("real.txt"), "class Foo {}");

        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("pattern", "class", "path", tempDir.toString()))
                .build();
        ToolExecutionResult result = grepTool.execute(req);

        assertTrue(result.isSuccess());
        Map<String, Object> data = (Map<String, Object>) result.getData();
        assertEquals(1, (int) data.get("count")); // 只找到 .txt 中的
    }
}
