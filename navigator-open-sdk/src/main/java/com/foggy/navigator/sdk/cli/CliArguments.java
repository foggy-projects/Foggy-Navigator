package com.foggy.navigator.sdk.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class CliArguments {
    private static final Set<String> KNOWN_OPTIONS = Set.of(
            "account-id",
            "admin-api-key",
            "admin-api-key-env",
            "admin-token",
            "admin-token-env",
            "agent",
            "agent-bundle-code",
            "agent-code",
            "agent-role",
            "allowed-tools",
            "api-key-env",
            "applicant-label",
            "approval-policy",
            "authorized-client-app-namespace",
            "authorized-tenant-id",
            "authorized-tenant-ids",
            "available-models",
            "base-url",
            "biz-worker-base-url",
            "biz-worker-data-root",
            "biz-worker-env-file",
            "biz-worker-id",
            "capability-domain",
            "category",
            "claim-token",
            "claim-token-env",
            "claim-ttl-minutes",
            "client-app-access-token",
            "client-app-access-token-env",
            "client-app-id",
            "client-app-key",
            "client-app-secret",
            "client-app-secret-env",
            "client-context-file",
            "client-context-json",
            "codex-home-key",
            "codex-provider-task-id",
            "codex-workspace-root",
            "context-id",
            "control-api-key",
            "control-api-key-env",
            "credential-expires-at",
            "credential-id",
            "cursor",
            "data-root",
            "default",
            "description",
            "directory-id",
            "dry-run",
            "effective-user-id",
            "expected-sha256",
            "expires-at",
            "file",
            "force",
            "from",
            "function-id",
            "grant-id",
            "grant-scope",
            "help",
            "install-shell",
            "interval",
            "limit",
            "manifest",
            "max-chars",
            "max-turns",
            "message",
            "mock-url",
            "model",
            "model-base-url",
            "model-config-id",
            "model-name",
            "model-profile-code",
            "model-variant",
            "multi-tenant",
            "name",
            "namespace",
            "network-access-enabled",
            "no-directory-required",
            "no-start",
            "operator-api-key",
            "operator-api-key-env",
            "owner-user-id",
            "path",
            "physical-worker-id",
            "pid",
            "poll",
            "pool-id",
            "private-account-id",
            "profile",
            "provider",
            "provider-task-id",
            "provider-type",
            "reason",
            "request-code",
            "requested-tenant-id",
            "rotate-credentials",
            "rotate-runtime-credential",
            "runtime-budget-override-json",
            "runtime-budget-preset",
            "sandbox-mode",
            "scope",
            "scopes",
            "set-default",
            "skill-id",
            "source-system",
            "source-tenant-id",
            "standard",
            "start-column",
            "start-line",
            "status",
            "target-tenant-id",
            "task-id",
            "tenant-id",
            "tenant-name",
            "tenant-profile",
            "timeout-seconds",
            "trace-id",
            "upstream-ref",
            "upstream-system-id",
            "upstream-user-id",
            "upstream-user-token",
            "upstream-user-token-env",
            "url",
            "user-api-key",
            "user-api-key-env",
            "user-token-header",
            "version",
            "web-search-mode",
            "worker-backend",
            "worker-host",
            "worker-id",
            "worker-pool-id",
            "workspace-root",
            "workspace-scope",
            "write-profile",
            "wsl-distro",
            "wsl-user",
            "yes");

    private final List<String> words;
    private final Map<String, String> options;

    private CliArguments(List<String> words, Map<String, String> options) {
        this.words = words;
        this.options = options;
    }

    static CliArguments parse(String[] args) {
        List<String> words = new ArrayList<>();
        Map<String, String> options = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--")) {
                String name = arg.substring(2);
                String value = "true";
                int eq = name.indexOf('=');
                if (eq >= 0) {
                    value = name.substring(eq + 1);
                    name = name.substring(0, eq);
                } else if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    value = args[++i];
                }
                options.put(name, value);
            } else {
                words.add(arg);
            }
        }
        if (!words.isEmpty() && "upstream".equals(words.get(0))) {
            words.remove(0);
        }
        return new CliArguments(words, options);
    }

    String command() {
        if (words.isEmpty()) {
            return "";
        }
        if (words.size() >= 2 && "config".equals(words.get(0)) && "check".equals(words.get(1))) {
            return "config check";
        }
        if (words.size() >= 2 && "diagnostics".equals(words.get(0))) {
            if ("session-dir".equals(words.get(1))) {
                return "diagnostics session-dir";
            }
            if ("help".equals(words.get(1))) {
                return "diagnostics help";
            }
        }
        if (words.size() >= 3 && "tms".equals(words.get(0))) {
            return String.join(" ", words.subList(0, Math.min(words.size(), 3)));
        }
        if (words.size() >= 2 && "skill".equals(words.get(0))) {
            return "skill " + words.get(1);
        }
        if (words.size() >= 2 && "agent".equals(words.get(0))) {
            return "agent " + words.get(1);
        }
        if (words.size() >= 2 && "function".equals(words.get(0))) {
            return "function " + words.get(1);
        }
        if (words.size() >= 2 && "route".equals(words.get(0))) {
            return "route " + words.get(1);
        }
        if (words.size() >= 2 && "account-context".equals(words.get(0))) {
            return "account-context " + words.get(1);
        }
        if (words.size() >= 2 && "admin-key".equals(words.get(0))) {
            return "admin-key " + words.get(1);
        }
        if (words.size() >= 2 && "client-app".equals(words.get(0))) {
            return "client-app " + words.get(1);
        }
        if (words.size() >= 2 && ("worker".equals(words.get(0))
                || "directory".equals(words.get(0))
                || "worker-host".equals(words.get(0))
                || "worker-pool".equals(words.get(0)))) {
            return words.get(0) + " " + words.get(1);
        }
        if (words.size() >= 2 && ("script".equals(words.get(0))
                || "debug".equals(words.get(0))
                || "model".equals(words.get(0)))) {
            return words.get(0) + " " + words.get(1);
        }
        return words.get(0);
    }

    String option(String name) {
        return options.get(name);
    }

    boolean flag(String name) {
        return Boolean.parseBoolean(options.getOrDefault(name, "false"));
    }

    void rejectUnknownOptions() {
        List<String> unknown = options.keySet().stream()
                .filter(option -> !KNOWN_OPTIONS.contains(option))
                .map(option -> "--" + option)
                .toList();
        if (unknown.isEmpty()) {
            return;
        }
        if (unknown.size() == 1) {
            throw new UpstreamCliException("Unknown option: " + unknown.get(0));
        }
        throw new UpstreamCliException("Unknown options: " + String.join(", ", unknown));
    }

    Map<String, String> options() {
        return options;
    }
}
