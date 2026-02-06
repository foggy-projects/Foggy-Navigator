package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shell 命令执行工具
 */
@Slf4j
@Component
public class BashTool implements BuiltInTool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 120;
    private static final int MAX_OUTPUT_LENGTH = 30000;

    @Override
    public String getName() {
        return "Bash";
    }

    @Override
    public String getDescription() {
        return "执行 shell 命令。用于 git、npm、docker 等终端操作。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();

        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "string");
        command.put("description", "要执行的命令");
        params.put("command", command);

        Map<String, Object> workingDir = new LinkedHashMap<>();
        workingDir.put("type", "string");
        workingDir.put("description", "工作目录，默认当前目录");
        params.put("working_dir", workingDir);

        Map<String, Object> timeout = new LinkedHashMap<>();
        timeout.put("type", "integer");
        timeout.put("description", "超时时间（秒），默认 120");
        params.put("timeout", timeout);

        return Map.of(
                "type", "object",
                "properties", params,
                "required", new String[]{"command"}
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> params = request.getParameters();
        String command = (String) params.get("command");

        if (command == null || command.isBlank()) {
            return ToolExecutionResult.error("INVALID_PARAM", "command is required");
        }

        String workingDir = (String) params.getOrDefault("working_dir", System.getProperty("user.dir"));
        int timeout = params.containsKey("timeout")
                ? ((Number) params.get("timeout")).intValue()
                : DEFAULT_TIMEOUT_SECONDS;

        // 安全检查：阻止危险命令
        String dangerCheck = checkDangerousCommand(command);
        if (dangerCheck != null) {
            return ToolExecutionResult.error("DANGEROUS_COMMAND", dangerCheck);
        }

        try {
            ProcessBuilder pb = createProcessBuilder(command, workingDir);
            pb.redirectErrorStream(true);

            Process process = pb.start();

            StringBuilder output = new StringBuilder();
            Charset charset = isWindows() ? Charset.forName("GBK") : Charset.defaultCharset();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), charset))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (output.length() < MAX_OUTPUT_LENGTH) {
                        output.append(line).append("\n");
                    }
                }
            }

            boolean completed = process.waitFor(timeout, TimeUnit.SECONDS);
            if (!completed) {
                process.destroyForcibly();
                return ToolExecutionResult.error("TIMEOUT", "Command timed out after " + timeout + " seconds");
            }

            int exitCode = process.exitValue();
            String outputStr = output.length() > MAX_OUTPUT_LENGTH
                    ? output.substring(0, MAX_OUTPUT_LENGTH) + "\n... (truncated)"
                    : output.toString();

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("output", outputStr);
            result.put("exitCode", exitCode);

            return ToolExecutionResult.builder()
                    .success(exitCode == 0)
                    .data(result)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (IOException e) {
            log.error("Failed to execute command: {}", command, e);
            return ToolExecutionResult.error("EXEC_ERROR", "Failed to execute: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolExecutionResult.error("INTERRUPTED", "Command was interrupted");
        }
    }

    private ProcessBuilder createProcessBuilder(String command, String workingDir) {
        ProcessBuilder pb;
        if (isWindows()) {
            pb = new ProcessBuilder("cmd", "/c", command);
        } else {
            pb = new ProcessBuilder("bash", "-c", command);
        }
        pb.directory(new java.io.File(workingDir));
        return pb;
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private String checkDangerousCommand(String command) {
        String lower = command.toLowerCase().trim();

        // 阻止格式化和删除根目录
        if (lower.contains("format ") || lower.contains("rm -rf /") || lower.contains("del /f /s /q c:")) {
            return "Potentially destructive command blocked";
        }

        // 阻止直接操作系统文件
        if (lower.contains("/etc/passwd") || lower.contains("/etc/shadow")) {
            return "Access to system files blocked";
        }

        // 阻止加密货币挖矿
        if (lower.contains("xmrig") || lower.contains("minerd") || lower.contains("cryptonight")) {
            return "Cryptocurrency mining blocked";
        }

        return null;
    }
}
