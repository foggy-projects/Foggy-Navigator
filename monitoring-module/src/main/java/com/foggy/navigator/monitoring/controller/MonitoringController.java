package com.foggy.navigator.monitoring.controller;

import com.foggy.navigator.monitoring.model.dto.MonitorEventDTO;
import com.foggy.navigator.monitoring.service.MonitorEventService;
import com.foggyframework.core.ex.RX;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/monitoring")
@RequiredArgsConstructor
public class MonitoringController {

    private final MonitorEventService eventService;

    /**
     * 分页查询监控事件
     *
     * @param service 服务名过滤（可选）
     * @param level   日志级别过滤（可选）
     * @param page    页码（从0开始）
     * @param size    每页大小
     */
    @GetMapping("/events")
    public RX<Page<MonitorEventDTO>> listEvents(
            @RequestParam(required = false) String service,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return RX.ok(eventService.query(service, level, page, size));
    }

    /**
     * 监控统计摘要
     */
    @GetMapping("/stats")
    public RX<Map<String, Object>> stats() {
        long errorsLast1h = eventService.countRecentErrors(1);
        long errorsLast24h = eventService.countRecentErrors(24);
        return RX.ok(Map.of(
                "errorsLast1h", errorsLast1h,
                "errorsLast24h", errorsLast24h
        ));
    }
}
