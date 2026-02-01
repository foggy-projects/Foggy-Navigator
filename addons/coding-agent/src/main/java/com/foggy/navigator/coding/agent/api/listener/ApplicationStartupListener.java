package com.foggy.navigator.coding.agent.api.listener;

import com.foggy.navigator.coding.agent.api.service.ConversationRecoveryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class ApplicationStartupListener {

    @Autowired
    private ConversationRecoveryService conversationRecoveryService;

    @Value("${foggy.coding-agent.recovery.auto-recover-on-startup:false}")
    private boolean autoRecoverOnStartup;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成");

        if (autoRecoverOnStartup) {
            log.info("启用自动会话恢复");
            try {
                conversationRecoveryService.recoverAllConversations();
                conversationRecoveryService.cleanupStoppedConversations();
            } catch (Exception e) {
                log.error("自动会话恢复失败", e);
            }
        } else {
            log.info("自动会话恢复未启用");
        }
    }
}
