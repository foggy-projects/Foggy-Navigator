package com.foggy.navigator.business.agent.model.dto;

import com.foggy.navigator.business.agent.model.entity.BizWorkerIdentityEntity;
import com.foggy.navigator.common.enums.ResourceOwnerType;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

import java.time.LocalDateTime;

@Data
public class BizWorkerCredentialDTO {

    private String workerId;
    private ResourceOwnerType ownerType;
    private String ownerId;
    private Integer credentialVersion;

    /**
     * One-time plaintext returned only by rotation. It is never read back from
     * persistence and is null for inspection/revocation responses.
    */
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private String secret;

    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private LocalDateTime rotatedAt;

    public static BizWorkerCredentialDTO fromEntity(
            BizWorkerIdentityEntity entity, String oneTimeSecret) {
        BizWorkerCredentialDTO dto = new BizWorkerCredentialDTO();
        dto.setWorkerId(entity.getWorkerId());
        dto.setOwnerType(entity.getOwnerType());
        dto.setOwnerId(entity.getOwnerId());
        dto.setCredentialVersion(entity.getCredentialVersion());
        dto.setSecret(oneTimeSecret);
        dto.setIssuedAt(entity.getCredentialIssuedAt());
        dto.setExpiresAt(entity.getCredentialExpiresAt());
        dto.setRevokedAt(entity.getCredentialRevokedAt());
        dto.setRotatedAt(entity.getCredentialRotatedAt());
        return dto;
    }
}
