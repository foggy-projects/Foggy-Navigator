package com.foggy.navigator.sdk.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class CliArguments {
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
        if (words.size() >= 3 && "tms".equals(words.get(0))) {
            return String.join(" ", words.subList(0, Math.min(words.size(), 3)));
        }
        if (words.size() >= 2 && "skill".equals(words.get(0))) {
            return "skill " + words.get(1);
        }
        if (words.size() >= 2 && "account-context".equals(words.get(0))) {
            return "account-context " + words.get(1);
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

    Map<String, String> options() {
        return options;
    }
}
