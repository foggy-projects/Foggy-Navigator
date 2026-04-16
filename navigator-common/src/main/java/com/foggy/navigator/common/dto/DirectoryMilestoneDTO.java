package com.foggy.navigator.common.dto;

import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DirectoryMilestoneDTO {
    private String id;
    private String name;
    private String status;
    private String docPath;
    private String startAt;
    private String endAt;
}
