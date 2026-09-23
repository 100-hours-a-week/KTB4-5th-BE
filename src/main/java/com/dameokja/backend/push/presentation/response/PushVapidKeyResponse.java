package com.dameokja.backend.push.presentation.response;

import com.dameokja.backend.push.domain.VapidKeyProperties;

public record PushVapidKeyResponse(String vapidPublicKey) {

    public static PushVapidKeyResponse from(VapidKeyProperties properties) {
        return new PushVapidKeyResponse(properties.getPublicKey());
    }
}
