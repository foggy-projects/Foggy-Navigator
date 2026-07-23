package com.foggy.navigator.sdk.model.businessagent;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class RuntimeRequestAuditPageDTO {
    private int count;
    private int limit;
    private List<RuntimeRequestAuditDTO> items;

    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
    public int getLimit() { return limit; }
    public void setLimit(int limit) { this.limit = limit; }
    public List<RuntimeRequestAuditDTO> getItems() { return items; }
    public void setItems(List<RuntimeRequestAuditDTO> items) { this.items = items; }
}
