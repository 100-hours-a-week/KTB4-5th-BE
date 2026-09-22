package com.dameokja.backend.push.presentation;

final class PushSubscriptionApiExamples {
    static final String SUBSCRIBE_REQUEST = """
            {
              "endpoint": "https://push.example.com/subscriptions/example",
              "keys": {
                "p256dh": "{BASE64URL_PUBLIC_KEY}",
                "auth": "{BASE64URL_AUTH_SECRET}"
              }
            }
            """;

    static final String SUBSCRIBE_CREATED_RESPONSE = """
            {
              "code": "PUSH-201-001",
              "message": "푸시 구독 생성 성공",
              "data": {
                "subscriptionId": "501"
              }
            }
            """;

    static final String SUBSCRIBE_RENEWED_RESPONSE = """
            {
              "code": "PUSH-200-001",
              "message": "푸시 구독 등록 성공",
              "data": {
                "subscriptionId": "501"
              }
            }
            """;

    private PushSubscriptionApiExamples() {
    }
}
