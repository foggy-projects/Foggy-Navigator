package com.foggy.navigator.business.agent.model.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RuntimeRequestAuditPageDTO {
    private int count;
    private int limit;
    private List<RuntimeRequestAuditDTO> items;
}
