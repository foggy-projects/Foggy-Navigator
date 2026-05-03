package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BusinessFunctionEntity;
import com.foggy.navigator.business.agent.model.entity.BusinessFunctionVersionEntity;
import lombok.Data;

@Data
public class BusinessFunctionRuntimeContextDTO {
    private String tenantId;
    private String functionId;
    private String version;
    private String manifestJson;
    private String inputSchemaJson;
    private String outputSchemaJson;
    private String adapterConfigJson;

    private BusinessFunctionDTO function;
    private BusinessFunctionVersionDTO versionData;

    public static BusinessFunctionRuntimeContextDTO fromEntity(BusinessFunctionEntity functionEntity, BusinessFunctionVersionEntity versionEntity) {
        if (versionEntity == null || functionEntity == null) {
            return null;
        }
        BusinessFunctionRuntimeContextDTO dto = new BusinessFunctionRuntimeContextDTO();
        dto.setTenantId(versionEntity.getTenantId());
        dto.setFunctionId(versionEntity.getFunctionId());
        dto.setVersion(versionEntity.getVersion());
        dto.setManifestJson(versionEntity.getManifestJson());
        dto.setInputSchemaJson(versionEntity.getInputSchemaJson());
        dto.setOutputSchemaJson(versionEntity.getOutputSchemaJson());
        dto.setAdapterConfigJson(versionEntity.getAdapterConfigJson());
        dto.setFunction(BusinessFunctionDTO.fromEntity(functionEntity));
        dto.setVersionData(BusinessFunctionVersionDTO.fromEntity(versionEntity));
        return dto;
    }
}
