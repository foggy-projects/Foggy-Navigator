package com.foggy.navigator.claude.worker.model.form;

import lombok.Data;

@Data
public class DirectoryMilestoneForm {
    private String id;
    private String name;
    private String status;
    private String docPath;
    private String startAt;
    private String endAt;
}
