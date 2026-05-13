package com.foggy.navigator.business.agent.model.dto;

import lombok.Data;

import java.util.List;

@Data
public class BusinessAgentSessionListDTO {
    private List<BusinessAgentSessionDTO> sessions;
    private String nextCursor;
    private boolean hasMore;
}
