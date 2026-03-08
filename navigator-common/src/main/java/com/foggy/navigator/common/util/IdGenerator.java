package com.foggy.navigator.common.util;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 生成短 ID，格式: "20260227-a3f2"（日期 + 4位十六进制随机码，共13字符）。
 * 用于 taskId、contextId、phaseId、directoryId 等场景，替代 UUID.substring。
 */
public final class IdGenerator {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.BASIC_ISO_DATE;

    private IdGenerator() {}

    /**
     * 生成短 ID: yyyyMMdd-xxxx (13 chars)
     */
    public static String shortId() {
        String date = LocalDate.now().format(DATE_FMT);
        int rand = ThreadLocalRandom.current().nextInt(0x10000);
        return date + "-" + String.format("%04x", rand);
    }
}
