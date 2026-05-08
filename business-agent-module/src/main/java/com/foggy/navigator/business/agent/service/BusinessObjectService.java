package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessObjectDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessObjectEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessObjectForm;
import com.foggy.navigator.business.agent.model.form.UpdateBusinessObjectForm;
import com.foggy.navigator.business.agent.repository.BusinessObjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class BusinessObjectService {

    public static final String STATUS_ENABLED = "ENABLED";
    public static final String STATUS_DISABLED = "DISABLED";

    private final BusinessObjectRepository businessObjectRepository;

    @Transactional
    public BusinessObjectDTO createBusinessObject(String tenantId, String actorUserId, CreateBusinessObjectForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }
        Assert.hasText(form.getObjectId(), "objectId is required");
        Assert.hasText(form.getName(), "name is required");

        String status = StringUtils.hasText(form.getStatus()) ? form.getStatus() : STATUS_ENABLED;
        if (!STATUS_ENABLED.equals(status) && !STATUS_DISABLED.equals(status)) {
            throw new IllegalArgumentException("invalid status");
        }

        if (businessObjectRepository.existsByObjectIdAndTenantId(form.getObjectId(), tenantId)) {
            throw new IllegalArgumentException("BusinessObject already exists for this tenant");
        }

        BusinessObjectEntity entity = new BusinessObjectEntity();
        entity.setTenantId(tenantId);
        entity.setObjectId(form.getObjectId());
        entity.setName(form.getName());
        entity.setDescription(form.getDescription());
        entity.setDomain(form.getDomain());
        entity.setStatus(status);
        entity.setCreatedBy(actorUserId);
        entity.setUpdatedBy(actorUserId);

        entity = businessObjectRepository.save(entity);
        return BusinessObjectDTO.fromEntity(entity);
    }

    @Transactional
    public BusinessObjectDTO updateBusinessObject(String tenantId, String objectId, String actorUserId, UpdateBusinessObjectForm form) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(objectId, "objectId is required");
        Assert.hasText(actorUserId, "actorUserId is required");
        if (form == null) {
            throw new IllegalArgumentException("form is required");
        }

        BusinessObjectEntity entity = businessObjectRepository.findByObjectIdAndTenantId(objectId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("BusinessObject not found"));

        if (StringUtils.hasText(form.getName())) {
            entity.setName(form.getName());
        }
        if (form.getDescription() != null) {
            entity.setDescription(form.getDescription());
        }
        if (form.getDomain() != null) {
            entity.setDomain(form.getDomain());
        }
        if (StringUtils.hasText(form.getStatus())) {
            if (!STATUS_ENABLED.equals(form.getStatus()) && !STATUS_DISABLED.equals(form.getStatus())) {
                throw new IllegalArgumentException("invalid status");
            }
            entity.setStatus(form.getStatus());
        }

        entity.setUpdatedBy(actorUserId);
        entity = businessObjectRepository.save(entity);
        return BusinessObjectDTO.fromEntity(entity);
    }

    @Transactional(readOnly = true)
    public BusinessObjectDTO getBusinessObject(String tenantId, String objectId) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(objectId, "objectId is required");

        BusinessObjectEntity entity = businessObjectRepository.findByObjectIdAndTenantId(objectId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("BusinessObject not found"));

        return BusinessObjectDTO.fromEntity(entity);
    }

    @Transactional(readOnly = true)
    public BusinessObjectEntity requireActiveBusinessObject(String tenantId, String objectId) {
        Assert.hasText(tenantId, "tenantId is required");
        Assert.hasText(objectId, "objectId is required");

        BusinessObjectEntity entity = businessObjectRepository.findByObjectIdAndTenantId(objectId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("BusinessObject not found: " + objectId));

        if (!STATUS_ENABLED.equals(entity.getStatus())) {
            throw new IllegalStateException("BusinessObject is not active: " + objectId);
        }

        return entity;
    }
}
