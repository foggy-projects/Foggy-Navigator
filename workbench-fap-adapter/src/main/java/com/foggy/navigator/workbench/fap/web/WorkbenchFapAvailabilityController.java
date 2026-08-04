package com.foggy.navigator.workbench.fap.web;

import com.foggy.navigator.common.context.UserContext;
import com.foggy.navigator.workbench.fap.config.WorkbenchFapProperties;
import com.foggy.navigator.workbench.fap.model.WorkbenchFapModels.Availability;
import com.foggy.navigator.workbench.fap.persistence.WorkbenchFapConversationBindingEntity;
import com.foggyframework.core.ex.RX;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Feature discovery only; packaging the module does not authorize the caller. */
@RestController
@RequestMapping("/api/v1/workbench/fap")
public class WorkbenchFapAvailabilityController {
    private final WorkbenchFapProperties properties;

    public WorkbenchFapAvailabilityController(WorkbenchFapProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/availability")
    public RX<Availability> availability() {
        String userId = UserContext.getCurrentUserId();
        return RX.ok(new Availability(
                true,
                properties.isEnabled(),
                properties.isEligible(userId),
                WorkbenchFapConversationBindingEntity.EXECUTION_LANE));
    }
}
