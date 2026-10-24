package com.dameokja.backend.push.presentation.request;

import com.dameokja.backend.push.presentation.request.validation.ValidPushSubscriptionKeys;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PushSubscriptionRequest(
        @NotBlank String endpoint,
        @NotNull @Valid Keys keys) {

    @ValidPushSubscriptionKeys
    public record Keys(@NotBlank @Size(max = 128) String p256dh, @NotBlank @Size(max = 128) String auth) {
        // auth 원문이 로그에 노출되지 않도록 기본 toString을 막는다.
        @Override
        public String toString() { return "Keys[redacted]"; }
    }

    // endpoint도 기기별 발송 주소라 로그에 남기지 않는다.
    @Override
    public String toString() { return "PushSubscriptionRequest[redacted]"; }
}
