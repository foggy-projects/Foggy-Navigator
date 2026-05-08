package com.foggy.navigator.business.agent.service.adapter;

import com.foggy.navigator.business.agent.model.dto.BusinessFunctionRuntimeContextDTO;

public interface BusinessFunctionAdapterInvoker {

    /**
     * Check if this invoker supports the given adapter type.
     *
     * @param type the type string (e.g., "echo", "rest")
     * @return true if supported
     */
    boolean supports(String type);

    /**
     * Invoke the business function adapter.
     *
     * @param context the executable runtime context including function, version, and adapter config
     * @param inputJson the normalized input JSON
     * @return the adapter execution result
     */
    BusinessFunctionAdapterResult invoke(BusinessFunctionRuntimeContextDTO context, String inputJson);
}
