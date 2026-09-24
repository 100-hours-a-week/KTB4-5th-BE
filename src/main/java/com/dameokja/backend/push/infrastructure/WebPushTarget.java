package com.dameokja.backend.push.infrastructure;

// auth는 복호화된 평문 비밀값이므로 toString에 노출하지 않는다.
public record WebPushTarget(String endpoint, String p256dhKey, String authSecret) {

    @Override
    public String toString() {
        return "WebPushTarget[endpoint=" + endpoint + "]";
    }
}
