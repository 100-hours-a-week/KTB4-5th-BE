package com.dameokja.backend.push.presentation;

final class PushVapidKeyApiExamples {
    static final String VAPID_KEY_RESPONSE = """
            {
              "code": "PUSH-200-001",
              "message": "VAPID 공개키 조회 성공",
              "data": {
                "vapidPublicKey": "BExampleVapidPublicKeyBase64Url...",
                "vapidKeyVersion": "v1"
              }
            }
            """;

    private PushVapidKeyApiExamples() {
    }
}
