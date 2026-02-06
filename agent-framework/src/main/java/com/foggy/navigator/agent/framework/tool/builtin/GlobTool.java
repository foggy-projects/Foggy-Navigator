package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 文件模式匹配工具
 */
@Slf4j
@Component
public class GlobTool implements BuiltInTool {

    private static final int MAX_RESULTS = 1000;

    @Override
    public String getName() {
        return "Glob";
    }

    @Override
    public String getDescription() {
        return "使用 glob 模式搜索文件。支持 **/*.java、src/**/*.ts 等模式。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();

        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "Glob 模式，如 **/*.java、src/**/*.ts");
        params.put("pattern", pattern);

        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "搜索的根目录，默认当前目录");
        params.put("path", path);

        return Map.of(
                "type", "object",
                "properties", params,
                "required", new String[]{"pattern"}
        );
    }

    @Override
    public ToolExecutionResult execute(ToolExecutionRequest request) {
        long startTime = System.currentTimeMillis();

        Map<String, Object> params = request.getParameters();
        String pattern = (String) params.get("pattern");

        if (pattern == null || pattern.isBlank()) {
            return ToolExecutionResult.error("INVALID_PARAM", "pattern is required");
        }

        String basePath = (String) params.getOrDefault("path", System.getProperty("user.dir"));
        Path searchRoot = Path.of(basePath);

        if (!Files.exists(searchRoot)) {
            return ToolExecutionResult.error("PATH_NOT_FOUND", "Path not found: " + basePath);
        }

        if (!Files.isDirectory(searchRoot)) {
            return ToolExecutionResult.error("NOT_A_DIRECTORY", "Path is not a directory: " + basePath);
        }

        try {
            List<FileInfo> matchedFiles = new ArrayList<>();
            PathMatcher matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);

            Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (matchedFiles.size() >= MAX_RESULTS) {
                        return FileVisitResult.TERMINATE;
                    }

                    Path relativePath = searchRoot.relativize(file);
                    if (matcher.matches(relativePath)) {
                        matchedFiles.add(new FileInfo(
                                file.toString(),
                                attrs.lastModifiedTime().toMillis(),
                                attrs.size()
                        ));
                    }
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    // 跳过隐藏目录和常见的排除目录
                    String dirName = dir.getFileName().toString();
                    if (dirName.startsWith(".") || dirName.equals("node_modules") || dirName.equals("target")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
            });

            // 按修改时间排序（最新的在前）
            matchedFiles.sort((a, b) -> Long.compare(b.lastModified, a.lastModified));

            List<String> paths = matchedFiles.stream()
                    .map(f -> f.path)
                    .collect(Collectors.toList());

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("files", paths);
            result.put("count", paths.size());
            result.put("truncated", matchedFiles.size() >= MAX_RESULTS);

            return ToolExecutionResult.builder()
                    .success(true)
                    .data(result)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (IOException e) {
            log.error("Failed to search files: {}", pattern, e);
            return ToolExecutionResult.error("SEARCH_ERROR", "Failed to search: " + e.getMessage());
        }
    }

    private record FileInfo(String path, long lastModified, long size) {}
}
