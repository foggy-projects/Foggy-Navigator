package com.foggy.navigator.langgraph.worker.model.form;

import lombok.Data;

/**
 * Form for approving/rejecting a pending task approval.
 */
@Data
public class ApproveTaskForm {

    /** "approved" or "rejected" */
    private String approvalResult;

    private String comment;

    /** Who is approving (if not inferred from auth context) */
    private String reviewedBy;
}
