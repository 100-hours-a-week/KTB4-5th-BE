package com.dameokja.backend.push.presentation.request;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PushSubscriptionRequestTest {

    @Test
    void toStringHidesEndpointAndKeys() {
        PushSubscriptionRequest request = new PushSubscriptionRequest("https://push.example.com/1",
                new PushSubscriptionRequest.Keys("p256dh-key", "auth-secret"));

        assertThat(request.toString())
                .doesNotContain("https://push.example.com/1", "p256dh-key", "auth-secret");
        assertThat(request.keys().toString()).doesNotContain("p256dh-key", "auth-secret");
    }
}
