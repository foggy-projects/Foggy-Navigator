package com.foggy.navigator.launcher;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * 启动校验：检测 navigator.api.external-url 是否配置为 localhost。
 * <p>
 * 在远端 Worker 或共享 API 场景下，如果 external-url 仍为 localhost，
 * 远端 AI / Worker 将无法回调平台。此校验在启动时输出明确告警，不阻断启动。
 */
@Component
public class ExternalUrlStartupValidator implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExternalUrlStartupValidator.class);

    @Value("${navigator.api.external-url:}")
    private String externalUrl;

    @Override
    public void run(ApplicationArguments args) {
        if (externalUrl == null || externalUrl.isBlank()) {
            log.warn("⚠ navigator.api.external-url is not configured. "
                    + "Remote Workers and Sharing Key consumers will default to localhost, "
                    + "which may be unreachable from other machines. "
                    + "Set NAVIGATOR_API_EXTERNAL_URL or navigator.api.external-url in application config.");
            return;
        }

        if (externalUrl.contains("localhost") || externalUrl.contains("127.0.0.1")) {
            log.warn("⚠ navigator.api.external-url is set to '{}'. "
                    + "This is fine for local development, but remote Workers and external "
                    + "Sharing Key consumers will not be able to reach this address. "
                    + "For production/LAN deployment, set it to the actual reachable address "
                    + "(e.g., http://192.168.x.x:8112).", externalUrl);
        } else {
            log.info("✓ navigator.api.external-url = {}", externalUrl);
        }
    }
}
