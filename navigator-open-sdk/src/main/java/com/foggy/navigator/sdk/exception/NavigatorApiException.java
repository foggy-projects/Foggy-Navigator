package com.foggy.navigator.sdk.exception;

/**
 * Navigator Open API 调用异常
 */
public class NavigatorApiException extends RuntimeException {

    private final int statusCode;
    private final String responseBody;

    public NavigatorApiException(String message) {
        super(message);
        this.statusCode = 0;
        this.responseBody = null;
    }

    public NavigatorApiException(String message, Throwable cause) {
        super(message, cause);
        this.statusCode = 0;
        this.responseBody = null;
    }

    public NavigatorApiException(int statusCode, String responseBody) {
        super("HTTP " + statusCode + ": " + extractMessage(responseBody));
        this.statusCode = statusCode;
        this.responseBody = responseBody;
    }

    public int getStatusCode() { return statusCode; }
    public String getResponseBody() { return responseBody; }

    private static String extractMessage(String body) {
        if (body == null) return "No response body";
        // Try to extract "msg" from RX response: {"code":..., "msg":"..."}
        int idx = body.indexOf("\"msg\"");
        if (idx >= 0) {
            int start = body.indexOf("\"", idx + 5);
            int end = body.indexOf("\"", start + 1);
            if (start >= 0 && end > start) {
                return body.substring(start + 1, end);
            }
        }
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }
}
