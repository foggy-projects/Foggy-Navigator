package com.foggy.navigator.business.agent.controller;

import com.foggy.navigator.common.annotation.RequireAuth;
import com.foggy.navigator.business.agent.model.dto.BusinessObjectDTO;
import com.foggy.navigator.business.agent.model.form.CreateBusinessObjectForm;
import com.foggy.navigator.business.agent.model.form.UpdateBusinessObjectForm;
import com.foggy.navigator.business.agent.service.BusinessObjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/business-agent/business-objects")
@RequiredArgsConstructor
public class BusinessObjectController {

    private final BusinessObjectService businessObjectService;

    @PostMapping
    @RequireAuth(roles = "TENANT_ADMIN")
    public BusinessObjectDTO createBusinessObject(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String userId,
            @RequestBody CreateBusinessObjectForm form) {
        return businessObjectService.createBusinessObject(tenantId, userId, form);
    }

    @GetMapping("/{objectId}")
    @RequireAuth(roles = "TENANT_ADMIN")
    public BusinessObjectDTO getBusinessObject(
            @RequestAttribute("tenantId") String tenantId,
            @PathVariable String objectId) {
        return businessObjectService.getBusinessObject(tenantId, objectId);
    }

    @PutMapping("/{objectId}")
    @RequireAuth(roles = "TENANT_ADMIN")
    public BusinessObjectDTO updateBusinessObject(
            @RequestAttribute("tenantId") String tenantId,
            @RequestAttribute("userId") String userId,
            @PathVariable String objectId,
            @RequestBody UpdateBusinessObjectForm form) {
        return businessObjectService.updateBusinessObject(tenantId, objectId, userId, form);
    }
}
