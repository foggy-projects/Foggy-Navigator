package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取文件工具
 */
@Slf4j
@Component
public class ReadTool implements BuiltInTool {

    private static final int DEFAULT_LIMIT = 2000;
    private static final int MAX_LINE_LENGTH = 2000;

    @Override
    public String getName() {
        return "Read";
    }

    @Override
    public String getDescription() {
        return "读取文件内容。支持指定起始行和行数限制。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "文件的绝对路径");
        params.put("file_path", filePath);

        Map<String, Object> offset = new LinkedHashMap<>();
        offset.put("type", "integer");
        offset.put("description", "起始行号（从1开始），默认1");
        params.put("offset", offset);

        Map<String, Object> limit = new LinkedHashMap<>();
        limit.put("type", "integer");
        limit.put("description", "读取的最大行数，默认2000");
        params.put("limit", limit);

        return Map.of(
                "type", "object",
                "properties", params,
                "required", new String[]{"file_path"}
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> params = request.getParameters();
        String filePath = (String) params.get("file_path");

        if (filePath == null || filePath.isBlank()) {
            return ToolExecutionResult.error("INVALID_PARAM", "file_path is required");
        }

        int offset = params.containsKey("offset") ? ((Number) params.get("offset")).intValue() : 1;
        int limit = params.containsKey("limit") ? ((Number) params.get("limit")).intValue() : DEFAULT_LIMIT;

        Path path = Path.of(filePath);
        if (!Files.exists(path)) {
            return ToolExecutionResult.error("FILE_NOT_FOUND", "File not found: " + filePath);
        }

        if (!Files.isRegularFile(path)) {
            return ToolExecutionResult.error("NOT_A_FILE", "Path is not a file: " + filePath);
        }

        try {
            var lines = Files.readAllLines(path, StandardCharsets.UTF_8);
            int totalLines = lines.size();

            // 调整 offset（1-based to 0-based）
            int startIndex = Math.max(0, offset - 1);
            int endIndex = Math.min(totalLines, startIndex + limit);

            StringBuilder content = new StringBuilder();
            for (int i = startIndex; i < endIndex; i++) {
                String line = lines.get(i);
                // 截断过长的行
                if (line.length() > MAX_LINE_LENGTH) {
                    line = line.substring(0, MAX_LINE_LENGTH) + "...";
                }
                // 添加行号
                content.append(String.format("%6d→%s%n", i + 1, line));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("content", content.toString());
            result.put("totalLines", totalLines);
            result.put("linesRead", endIndex - startIndex);
            result.put("fromLine", startIndex + 1);
            result.put("toLine", endIndex);

            return ToolExecutionResult.builder()
                    .success(true)
                    .data(result)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (IOException e) {
            log.error("Failed to read file: {}", filePath, e);
            return ToolExecutionResult.error("READ_ERROR", "Failed to read file: " + e.getMessage());
        }
    }
}
