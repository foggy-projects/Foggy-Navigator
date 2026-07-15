package com.foggyframework.core.ex;

/**
 * Minimal business-exception contract consumed by Navigator's exception handler.
 */
public interface ExRuntimeException {

    int getCode();

    String getExCode();

    String getMessage();

    default RX<Void> toR() {
        return new RX<>(getCode(), getExCode(), getMessage(), null);
    }
}
