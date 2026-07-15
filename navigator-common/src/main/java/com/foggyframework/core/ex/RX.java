package com.foggyframework.core.ex;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;

/**
 * Navigator-owned compatibility envelope for the existing REST wire format.
 *
 * <p>The legacy {@code foggy-core} artifact is not available from the clean-runner
 * repository set. This deliberately small implementation preserves only the API
 * surface used by Navigator while callers migrate independently of that artifact.</p>
 *
 * @param <T> response payload type
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RX<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final int SUCCESS = 200;
    public static final int COMMON_ERROR = 600;
    public static final String A_COMMON = "A600";
    public static final String B_COMMON = "B600";

    private int code;
    private String exCode;
    private String msg;
    private T data;

    public RX() {
    }

    public RX(int code, String exCode, String msg, T data) {
        this.code = code;
        this.exCode = exCode;
        this.msg = msg;
        this.data = data;
    }

    public static <T> RX<T> ok() {
        return new RX<>(SUCCESS, null, null, null);
    }

    public static <T> RX<T> ok(T data) {
        return new RX<>(SUCCESS, null, null, data);
    }

    public static <T> RX<T> failA(String msg) {
        return new RX<>(COMMON_ERROR, A_COMMON, msg, null);
    }

    public static <T> RX<T> failB(String msg) {
        return new RX<>(COMMON_ERROR, B_COMMON, msg, null);
    }

    public static <T> RX<T> error(String msg) {
        return RX.<T>failB(msg);
    }

    public static ExRuntimeExceptionImpl throwB(String msg) {
        return new ExRuntimeExceptionImpl(COMMON_ERROR, B_COMMON, msg);
    }

    @JsonIgnore
    public boolean isOk() {
        return code == SUCCESS;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getExCode() {
        return exCode;
    }

    public void setExCode(String exCode) {
        this.exCode = exCode;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
