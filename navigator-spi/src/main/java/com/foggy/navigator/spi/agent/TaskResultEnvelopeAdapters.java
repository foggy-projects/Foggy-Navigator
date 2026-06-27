package com.foggy.navigator.spi.agent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

final class TaskResultEnvelopeAdapters {

    private TaskResultEnvelopeAdapters() {
    }

    static List<Object> listProperty(Object target, String property) {
        Object value = readProperty(target, property);
        if (value instanceof Collection<?> collection) {
            return new ArrayList<>(collection);
        }
        return List.of();
    }

    static long longProperty(Object target, String property, long defaultValue) {
        Object value = readProperty(target, property);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Long.parseLong(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static int intProperty(Object target, String property, int defaultValue) {
        Object value = readProperty(target, property);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            try {
                return Integer.parseInt(text);
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    static Object readProperty(Object target, String property) {
        if (target == null) {
            return null;
        }
        if (target instanceof Map<?, ?> map) {
            return map.get(property);
        }
        try {
            Method getter = target.getClass().getMethod(
                    "get" + Character.toUpperCase(property.charAt(0)) + property.substring(1));
            return getter.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }
}
