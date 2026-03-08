package com.foggy.navigator.agent.framework.tool.config;

import com.foggy.navigator.agent.framework.tool.BuiltInTool;
import com.foggy.navigator.agent.framework.tool.ToolRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 内置工具自动配置
 * 启动时注册所有 BuiltInTool 实现
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class BuiltInToolConfiguration {

    private final ToolRegistry toolRegistry;
    private final List<BuiltInTool> builtInTools;

    @PostConstruct
    public void registerBuiltInTools() {
        log.info("Registering {} built-in tools...", builtInTools.size());
        for (BuiltInTool tool : builtInTools) {
            toolRegistry.registerBuiltInTool(tool);
        }
        log.info("Built-in tools registration complete");
    }
}
