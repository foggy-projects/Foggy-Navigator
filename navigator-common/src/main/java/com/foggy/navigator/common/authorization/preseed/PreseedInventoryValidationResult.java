package com.foggy.navigator.common.authorization.preseed;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Deliberately small public-safe result. It never contains raw input or a
 * secret-bearing diagnostic. A non-null record alias has already passed the
 * opaque alias validation performed by {@link PreseedInventoryValidator}.
 */
public record PreseedInventoryValidationResult(
        PreseedInventoryClassification classification,
        PreseedInventoryReasonCode reasonCode,
        String recordAlias,
        int recordCount,
        String checksum
) {

    private static final Pattern SAFE_ALIAS = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{2,63}");
    private static final Pattern SAFE_CHECKSUM = Pattern.compile("[a-f0-9]{64}");

    public PreseedInventoryValidationResult {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(reasonCode, "reasonCode");
        if (recordCount < 0) {
            throw new IllegalArgumentException("recordCount must not be negative");
        }
        if (recordAlias != null && !SAFE_ALIAS.matcher(recordAlias).matches()) {
            throw new IllegalArgumentException("recordAlias must be an opaque safe alias");
        }
        if (checksum != null && !SAFE_CHECKSUM.matcher(checksum).matches()) {
            throw new IllegalArgumentException("checksum must be lowercase SHA-256");
        }
        if (classification == PreseedInventoryClassification.VALID
                && reasonCode != PreseedInventoryReasonCode.PRESEED_VALID) {
            throw new IllegalArgumentException("VALID requires PRESEED_VALID");
        }
        if (classification != PreseedInventoryClassification.VALID
                && reasonCode == PreseedInventoryReasonCode.PRESEED_VALID) {
            throw new IllegalArgumentException("PRESEED_VALID requires VALID");
        }
        if (classification != PreseedInventoryClassification.QUARANTINED && recordAlias != null) {
            throw new IllegalArgumentException("only QUARANTINED may expose a record alias");
        }
    }

    static PreseedInventoryValidationResult valid(int recordCount, String checksum) {
        return new PreseedInventoryValidationResult(
                PreseedInventoryClassification.VALID,
                PreseedInventoryReasonCode.PRESEED_VALID,
                null,
                recordCount,
                checksum);
    }

    static PreseedInventoryValidationResult invalid(PreseedInventoryReasonCode reasonCode,
                                                     int recordCount,
                                                     String checksum) {
        return new PreseedInventoryValidationResult(
                PreseedInventoryClassification.INVALID,
                reasonCode,
                null,
                recordCount,
                checksum);
    }

    static PreseedInventoryValidationResult quarantined(PreseedInventoryReasonCode reasonCode,
                                                         String recordAlias,
                                                         int recordCount,
                                                         String checksum) {
        return new PreseedInventoryValidationResult(
                PreseedInventoryClassification.QUARANTINED,
                reasonCode,
                recordAlias,
                recordCount,
                checksum);
    }
}
