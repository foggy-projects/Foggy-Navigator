package com.foggy.navigator.common.event;

import com.foggy.navigator.common.entity.DatasourceConfigEntity;
import com.foggy.navigator.common.enums.ConfigItemStatus;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

@Getter
public class DatasourceConfigEvent extends ApplicationEvent {

    private final DatasourceConfigEntity config;
    private final EventType eventType;
    private final ConfigItemStatus previousStatus;
    private final ConfigItemStatus currentStatus;

    public enum EventType {
        CREATED,
        UPDATED,
        DELETED,
        STATUS_CHANGED
    }

    private DatasourceConfigEvent(Object source, DatasourceConfigEntity config, EventType eventType,
                                   ConfigItemStatus previousStatus, ConfigItemStatus currentStatus) {
        super(source);
        this.config = config;
        this.eventType = eventType;
        this.previousStatus = previousStatus;
        this.currentStatus = currentStatus;
    }

    public static DatasourceConfigEvent created(Object source, DatasourceConfigEntity config) {
        return new DatasourceConfigEvent(source, config, EventType.CREATED, null, config.getStatus());
    }

    public static DatasourceConfigEvent updated(Object source, DatasourceConfigEntity config) {
        return new DatasourceConfigEvent(source, config, EventType.UPDATED, null, config.getStatus());
    }

    public static DatasourceConfigEvent deleted(Object source, DatasourceConfigEntity config) {
        return new DatasourceConfigEvent(source, config, EventType.DELETED, config.getStatus(), null);
    }

    public static DatasourceConfigEvent statusChanged(Object source, DatasourceConfigEntity config,
                                                       ConfigItemStatus previousStatus, ConfigItemStatus currentStatus) {
        return new DatasourceConfigEvent(source, config, EventType.STATUS_CHANGED, previousStatus, currentStatus);
    }
}
