package com.foggy.navigator.business.agent.service;

import com.foggy.navigator.business.agent.model.dto.BusinessObjectDTO;
import com.foggy.navigator.business.agent.model.entity.BusinessObjectEntity;
import com.foggy.navigator.business.agent.model.form.CreateBusinessObjectForm;
import com.foggy.navigator.business.agent.model.form.UpdateBusinessObjectForm;
import com.foggy.navigator.business.agent.repository.BusinessObjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BusinessObjectServiceTest {

    @Mock
    private BusinessObjectRepository businessObjectRepository;

    @InjectMocks
    private BusinessObjectService businessObjectService;

    @Test
    void createBusinessObject_success() {
        CreateBusinessObjectForm form = new CreateBusinessObjectForm();
        form.setObjectId("obj_01");
        form.setName("Test Object");

        when(businessObjectRepository.existsByObjectIdAndTenantId("obj_01", "tenant_01")).thenReturn(false);
        when(businessObjectRepository.save(any(BusinessObjectEntity.class))).thenAnswer(i -> i.getArgument(0));

        BusinessObjectDTO result = businessObjectService.createBusinessObject("tenant_01", "actor_01", form);

        assertNotNull(result);
        assertEquals("obj_01", result.getObjectId());
        assertEquals("Test Object", result.getName());
        assertEquals(BusinessObjectService.STATUS_ENABLED, result.getStatus());
    }

    @Test
    void createBusinessObject_duplicate_rejected() {
        CreateBusinessObjectForm form = new CreateBusinessObjectForm();
        form.setObjectId("obj_01");
        form.setName("Test Object");

        when(businessObjectRepository.existsByObjectIdAndTenantId("obj_01", "tenant_01")).thenReturn(true);

        assertThrows(IllegalArgumentException.class, () ->
            businessObjectService.createBusinessObject("tenant_01", "actor_01", form)
        );
    }

    @Test
    void updateBusinessObject_success() {
        UpdateBusinessObjectForm form = new UpdateBusinessObjectForm();
        form.setName("Updated Object");
        form.setStatus(BusinessObjectService.STATUS_DISABLED);

        BusinessObjectEntity entity = new BusinessObjectEntity();
        entity.setObjectId("obj_01");
        entity.setName("Test Object");
        entity.setStatus(BusinessObjectService.STATUS_ENABLED);

        when(businessObjectRepository.findByObjectIdAndTenantId("obj_01", "tenant_01")).thenReturn(Optional.of(entity));
        when(businessObjectRepository.save(any(BusinessObjectEntity.class))).thenAnswer(i -> i.getArgument(0));

        BusinessObjectDTO result = businessObjectService.updateBusinessObject("tenant_01", "obj_01", "actor_01", form);

        assertNotNull(result);
        assertEquals("Updated Object", result.getName());
        assertEquals(BusinessObjectService.STATUS_DISABLED, result.getStatus());
    }

    @Test
    void requireActiveBusinessObject_success() {
        BusinessObjectEntity entity = new BusinessObjectEntity();
        entity.setObjectId("obj_01");
        entity.setStatus(BusinessObjectService.STATUS_ENABLED);

        when(businessObjectRepository.findByObjectIdAndTenantId("obj_01", "tenant_01")).thenReturn(Optional.of(entity));

        BusinessObjectEntity result = businessObjectService.requireActiveBusinessObject("tenant_01", "obj_01");

        assertNotNull(result);
    }

    @Test
    void requireActiveBusinessObject_disabled_rejected() {
        BusinessObjectEntity entity = new BusinessObjectEntity();
        entity.setObjectId("obj_01");
        entity.setStatus(BusinessObjectService.STATUS_DISABLED);

        when(businessObjectRepository.findByObjectIdAndTenantId("obj_01", "tenant_01")).thenReturn(Optional.of(entity));

        assertThrows(IllegalStateException.class, () ->
            businessObjectService.requireActiveBusinessObject("tenant_01", "obj_01")
        );
    }
}
