package com.dameokja.backend.push.presentation.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PushSubscriptionRequest(
        @NotBlank String endpoint,
        @NotNull @Valid Keys keys) {

    public record Keys(@NotBlank String p256dh, @NotBlank String auth) {
        // auth 원문이 로그에 노출되지 않도록 기본 toString을 막는다.
        @Override
        public String toString() { return "Keys[redacted]"; }
    }

    @Override
    public String toString() {
        return "PushSubscriptionRequest[endpoint=" + endpoint + ", keys=redacted]";
    }
}
