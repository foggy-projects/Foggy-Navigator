package com.foggy.navigator.business.agent.service.adapter;

import lombok.Data;

@Data
public class BusinessFunctionAdapterResult {
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_ADAPTER_ERROR = "ADAPTER_ERROR";

    private String status;
    private String outputJson;
    private String message;

    public static BusinessFunctionAdapterResult success(String outputJson) {
        BusinessFunctionAdapterResult result = new BusinessFunctionAdapterResult();
        result.setStatus(STATUS_SUCCESS);
        result.setOutputJson(outputJson);
        result.setMessage("Adapter execution successful");
        return result;
    }

    public static BusinessFunctionAdapterResult error(String message) {
        BusinessFunctionAdapterResult result = new BusinessFunctionAdapterResult();
        result.setStatus(STATUS_ADAPTER_ERROR);
        result.setMessage(message);
        return result;
    }
}
