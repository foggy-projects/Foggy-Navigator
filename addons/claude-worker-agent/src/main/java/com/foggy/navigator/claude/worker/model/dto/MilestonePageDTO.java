package com.foggy.navigator.claude.worker.model.dto;

import com.foggy.navigator.common.dto.DirectoryMilestoneDTO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 工作目录里程碑分页响应。
 */
@Data
@Builder
public class MilestonePageDTO {
    private List<DirectoryMilestoneDTO> content;
    private long total;
    private int page;
    private int size;
    private String sortBy;
    private String sortDir;
}
