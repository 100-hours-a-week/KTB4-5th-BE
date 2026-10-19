package com.dameokja.backend.user.presentation;

final class OAuthSignupApiExamples {
    static final String REQUEST = """
            {"nickname":"dave","notificationSetting":true}
            """;
    static final String RESPONSE = """
            {"code":"USER-201-002","message":"회원가입 성공","data":{"activeRefrigeratorIds":["1"]}}
            """;

    private OAuthSignupApiExamples() {}
}
