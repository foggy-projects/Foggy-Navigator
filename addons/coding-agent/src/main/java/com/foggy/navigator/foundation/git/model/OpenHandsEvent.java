package com.foggy.navigator.foundation.git.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenHandsEvent {

    private String id;

    private String conversation_id;

    private String kind;

    private String timestamp;

    private Map<String, Object> data;
}
