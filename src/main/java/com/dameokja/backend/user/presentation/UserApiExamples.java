package com.dameokja.backend.user.presentation;

final class UserApiExamples {
    static final String SIGNUP_REQUEST = """
            {
              "loginId": "user1",
              "password": "password1",
              "nickname": "냉장고요정"
            }
            """;

    static final String SIGNUP_RESPONSE = """
            {
              "code": "USER-201-001",
              "message": "회원가입 성공",
              "data": {
                "activeRefrigeratorIds": ["1"]
              }
            }
            """;

    private UserApiExamples() {}
}
