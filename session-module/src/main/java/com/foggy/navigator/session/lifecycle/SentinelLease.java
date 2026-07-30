package com.foggy.navigator.session.lifecycle;

public record SentinelLease(String physicalWorkerId, String holderId, long fenceToken) {
}
