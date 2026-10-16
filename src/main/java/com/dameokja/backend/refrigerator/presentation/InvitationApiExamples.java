package com.dameokja.backend.refrigerator.presentation;

final class InvitationApiExamples {
    static final String ISSUED_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-002",
              "message": "초대코드 발급 성공",
              "data": {"code": "P2F32Y"}
            }
            """;
    static final String RETRIEVED_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-006",
              "message": "초대코드 조회 성공",
              "data": {"code": "P2F32Y", "expiresAt": "2026-10-13T12:05:00+09:00"}
            }
            """;
    static final String EMPTY_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-006",
              "message": "초대코드 조회 성공",
              "data": null
            }
            """;

    private InvitationApiExamples() {
    }
}
