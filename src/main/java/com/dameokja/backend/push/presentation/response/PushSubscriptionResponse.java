package com.dameokja.backend.push.presentation.response;

public record PushSubscriptionResponse(String subscriptionId) {

    public static PushSubscriptionResponse from(Long subscriptionId) {
        return new PushSubscriptionResponse(String.valueOf(subscriptionId));
    }
}
