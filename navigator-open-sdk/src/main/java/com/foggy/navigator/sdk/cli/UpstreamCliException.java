package com.foggy.navigator.sdk.cli;

final class UpstreamCliException extends RuntimeException {
    UpstreamCliException(String message) {
        super(message);
    }

    UpstreamCliException(String message, Throwable cause) {
        super(message, cause);
    }
}
