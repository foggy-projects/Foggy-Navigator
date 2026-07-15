package com.foggyframework.core.ex;

/**
 * Runtime form of the Navigator compatibility response error.
 */
public class ExRuntimeExceptionImpl extends RuntimeException implements ExRuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;
    private final String exCode;

    public ExRuntimeExceptionImpl(int code, String exCode, String message) {
        super(message);
        this.code = code;
        this.exCode = exCode;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getExCode() {
        return exCode;
    }
}
