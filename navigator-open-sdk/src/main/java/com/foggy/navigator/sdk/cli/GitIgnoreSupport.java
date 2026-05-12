package com.foggy.navigator.sdk.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class GitIgnoreSupport {
    private GitIgnoreSupport() {
    }

    static boolean isIgnored(Path cwd, Path path) {
        if (checkGit(cwd, path)) {
            return true;
        }
        return checkRootGitIgnore(cwd, path);
    }

    private static boolean checkGit(Path cwd, Path path) {
        try {
            Process process = new ProcessBuilder("git", "check-ignore", "-q",
                    cwd.relativize(path).toString())
                    .directory(cwd.toFile())
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException | IllegalArgumentException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    private static boolean checkRootGitIgnore(Path cwd, Path path) {
        Path gitignore = cwd.resolve(".gitignore");
        if (!Files.exists(gitignore)) {
            return false;
        }
        String relative;
        try {
            relative = cwd.relativize(path).toString().replace('\\', '/');
        } catch (IllegalArgumentException e) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(gitignore);
            for (String raw : lines) {
                String line = raw.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) {
                    continue;
                }
                if (line.endsWith("/") && relative.startsWith(line.substring(0, line.length() - 1))) {
                    return true;
                }
                if (line.equals(relative) || line.equals("/" + relative)) {
                    return true;
                }
                if (line.startsWith("*.") && relative.endsWith(line.substring(1))) {
                    return true;
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }
}
