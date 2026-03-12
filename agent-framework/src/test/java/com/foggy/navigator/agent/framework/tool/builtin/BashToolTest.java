package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BashTool 单元测试 — L1
 * 只测试参数校验和安全检查逻辑，不执行真实 shell 命令
 */
class BashToolTest {

    private final BashTool bashTool = new BashTool();

    // ---- 元数据 ----

    @Test
    void name() {
        assertEquals("Bash", bashTool.getName());
    }

    @Test
    void parameters_containsCommand() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) bashTool.getParameters().get("properties");
        assertTrue(props.containsKey("command"));
        assertTrue(props.containsKey("working_dir"));
        assertTrue(props.containsKey("timeout"));
    }

    // ---- 参数校验 ----

    @Test
    void execute_nullCommand_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of())
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("INVALID_PARAM", result.getErrorCode());
    }

    @Test
    void execute_blankCommand_error() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "   "))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertFalse(result.isSuccess());
    }

    // ---- 安全检查 ----

    @Test
    void execute_dangerousRmRf_blocked() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "rm -rf /"))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("DANGEROUS_COMMAND", result.getErrorCode());
    }

    @Test
    void execute_dangerousFormat_blocked() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "format C:"))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("DANGEROUS_COMMAND", result.getErrorCode());
    }

    @Test
    void execute_systemFileAccess_blocked() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "cat /etc/shadow"))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("DANGEROUS_COMMAND", result.getErrorCode());
    }

    @Test
    void execute_cryptoMining_blocked() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "xmrig --pool stratum://mine.pool"))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertFalse(result.isSuccess());
        assertEquals("DANGEROUS_COMMAND", result.getErrorCode());
    }

    // ---- 正常执行（简单安全命令）----

    @Test
    void execute_echoCommand_success() {
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "echo hello"))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertTrue(result.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) result.getData();
        String output = (String) data.get("output");
        assertTrue(output.contains("hello"));
    }

    @Test
    void execute_withWorkingDir_success() {
        String dir = System.getProperty("user.dir");
        ToolExecutionRequest req = ToolExecutionRequest.builder()
                .parameters(Map.of("command", "echo test", "working_dir", dir))
                .build();
        ToolExecutionResult result = bashTool.execute(req);
        assertTrue(result.isSuccess());
    }
}
