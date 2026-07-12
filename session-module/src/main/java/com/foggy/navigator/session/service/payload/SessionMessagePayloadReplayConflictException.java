package com.foggy.navigator.session.service.payload;

/**
 * A stable message id already belongs to different immutable payload bytes or
 * message context. This is a producer/replay contract violation, not an
 * availability fault: the event must remain unacknowledged so it cannot
 * overwrite or misrepresent the first durable payload.
 */
public class SessionMessagePayloadReplayConflictException extends RuntimeException {

    public static final String CODE = "SESSION_MESSAGE_PAYLOAD_REPLAY_CONFLICT";

    public SessionMessagePayloadReplayConflictException(String message) {
        super(message);
    }

    public SessionMessagePayloadReplayConflictException(String message, Throwable cause) {
        super(message, cause);
    }

    public String code() {
        return CODE;
    }
}
