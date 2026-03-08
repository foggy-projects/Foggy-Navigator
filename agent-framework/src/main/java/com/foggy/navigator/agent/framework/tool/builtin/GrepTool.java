package com.foggy.navigator.agent.framework.tool.builtin;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolExecutionRequest;
import com.foggy.navigator.agent.framework.tool.ToolExecutionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 内容搜索工具
 */
@Slf4j
@Component
public class GrepTool implements BuiltInTool {

    private static final int MAX_MATCHES = 500;
    private static final int MAX_LINE_LENGTH = 500;

    @Override
    public String getName() {
        return "Grep";
    }

    @Override
    public String getDescription() {
        return "搜索文件内容。支持正则表达式，可按文件类型过滤。";
    }

    @Override
    public Map<String, Object> getParameters() {
        Map<String, Object> params = new LinkedHashMap<>();

        Map<String, Object> pattern = new LinkedHashMap<>();
        pattern.put("type", "string");
        pattern.put("description", "搜索模式（正则表达式）");
        params.put("pattern", pattern);

        Map<String, Object> path = new LinkedHashMap<>();
        path.put("type", "string");
        path.put("description", "搜索的目录或文件，默认当前目录");
        params.put("path", path);

        Map<String, Object> glob = new LinkedHashMap<>();
        glob.put("type", "string");
        glob.put("description", "文件过滤模式，如 *.java、**/*.ts");
        params.put("glob", glob);

        Map<String, Object> caseInsensitive = new LinkedHashMap<>();
        caseInsensitive.put("type", "boolean");
        caseInsensitive.put("description", "是否忽略大小写，默认 false");
        params.put("case_insensitive", caseInsensitive);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("type", "integer");
        context.put("description", "显示匹配行前后的上下文行数，默认 0");
        params.put("context", context);

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
        String patternStr = (String) params.get("pattern");

        if (patternStr == null || patternStr.isBlank()) {
            return ToolExecutionResult.error("INVALID_PARAM", "pattern is required");
        }

        String basePath = (String) params.getOrDefault("path", System.getProperty("user.dir"));
        String globPattern = (String) params.get("glob");
        boolean caseInsensitive = Boolean.TRUE.equals(params.get("case_insensitive"));
        int contextLines = params.containsKey("context") ? ((Number) params.get("context")).intValue() : 0;

        Path searchRoot = Path.of(basePath);
        if (!Files.exists(searchRoot)) {
            return ToolExecutionResult.error("PATH_NOT_FOUND", "Path not found: " + basePath);
        }

        Pattern regex;
        try {
            int flags = caseInsensitive ? Pattern.CASE_INSENSITIVE : 0;
            regex = Pattern.compile(patternStr, flags);
        } catch (PatternSyntaxException e) {
            return ToolExecutionResult.error("INVALID_PATTERN", "Invalid regex: " + e.getMessage());
        }

        PathMatcher fileMatcher = globPattern != null
                ? FileSystems.getDefault().getPathMatcher("glob:" + globPattern)
                : null;

        try {
            List<Match> matches = new ArrayList<>();

            if (Files.isRegularFile(searchRoot)) {
                searchFile(searchRoot, regex, contextLines, matches);
            } else {
                Files.walkFileTree(searchRoot, new SimpleFileVisitor<>() {
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (matches.size() >= MAX_MATCHES) {
                            return FileVisitResult.TERMINATE;
                        }

                        // 过滤二进制文件
                        String fileName = file.getFileName().toString();
                        if (isBinaryFile(fileName)) {
                            return FileVisitResult.CONTINUE;
                        }

                        // 应用 glob 过滤
                        if (fileMatcher != null) {
                            Path relativePath = searchRoot.relativize(file);
                            if (!fileMatcher.matches(relativePath)) {
                                return FileVisitResult.CONTINUE;
                            }
                        }

                        searchFile(file, regex, contextLines, matches);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException exc) {
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                        String dirName = dir.getFileName().toString();
                        if (dirName.startsWith(".") || dirName.equals("node_modules") || dirName.equals("target")) {
                            return FileVisitResult.SKIP_SUBTREE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("matches", matches);
            result.put("count", matches.size());
            result.put("truncated", matches.size() >= MAX_MATCHES);

            return ToolExecutionResult.builder()
                    .success(true)
                    .data(result)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .build();

        } catch (IOException e) {
            log.error("Failed to search: {}", patternStr, e);
            return ToolExecutionResult.error("SEARCH_ERROR", "Failed to search: " + e.getMessage());
        }
    }

    private void searchFile(Path file, Pattern regex, int contextLines, List<Match> matches) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);

            for (int i = 0; i < lines.size() && matches.size() < MAX_MATCHES; i++) {
                String line = lines.get(i);
                Matcher matcher = regex.matcher(line);

                if (matcher.find()) {
                    String content = line.length() > MAX_LINE_LENGTH
                            ? line.substring(0, MAX_LINE_LENGTH) + "..."
                            : line;

                    List<String> before = new ArrayList<>();
                    List<String> after = new ArrayList<>();

                    if (contextLines > 0) {
                        for (int j = Math.max(0, i - contextLines); j < i; j++) {
                            before.add(truncateLine(lines.get(j)));
                        }
                        for (int j = i + 1; j <= Math.min(lines.size() - 1, i + contextLines); j++) {
                            after.add(truncateLine(lines.get(j)));
                        }
                    }

                    matches.add(new Match(
                            file.toString(),
                            i + 1,
                            content,
                            before.isEmpty() ? null : before,
                            after.isEmpty() ? null : after
                    ));
                }
            }
        } catch (IOException e) {
            // 忽略无法读取的文件
        }
    }

    private String truncateLine(String line) {
        return line.length() > MAX_LINE_LENGTH ? line.substring(0, MAX_LINE_LENGTH) + "..." : line;
    }

    private boolean isBinaryFile(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".jar") || lower.endsWith(".class") || lower.endsWith(".zip")
                || lower.endsWith(".tar") || lower.endsWith(".gz") || lower.endsWith(".png")
                || lower.endsWith(".jpg") || lower.endsWith(".gif") || lower.endsWith(".ico")
                || lower.endsWith(".exe") || lower.endsWith(".dll") || lower.endsWith(".so")
                || lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".xls");
    }

    private record Match(String file, int line, String content, List<String> before, List<String> after) {}
}
