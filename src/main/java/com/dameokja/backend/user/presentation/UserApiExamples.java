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

    static final String NICKNAME_VALIDATION_REQUEST = """
            {
              "nickname": "냉장고요정"
            }
            """;

    static final String NICKNAME_VALIDATION_AVAILABLE = """
            {
              "code": "USER-200-001",
              "message": "사용 가능한 닉네임",
              "data": {
                "valid": true,
                "reason": null
              }
            }
            """;

    static final String NICKNAME_VALIDATION_INVALID_FORMAT = """
            {
              "code": "USER-200-001",
              "message": "닉네임은 2~10자, 한글·영문·숫자만 가능합니다.",
              "data": {
                "valid": false,
                "reason": "닉네임 형식 위반"
              }
            }
            """;

    static final String NICKNAME_VALIDATION_PROHIBITED = """
            {
              "code": "USER-200-001",
              "message": "부적절한 닉네임입니다.",
              "data": {
                "valid": false,
                "reason": "금칙어"
              }
            }
            """;

    static final String NICKNAME_VALIDATION_DUPLICATE = """
            {
              "code": "USER-200-001",
              "message": "중복된 닉네임입니다.",
              "data": {
                "valid": false,
                "reason": "닉네임 중복"
              }
            }
            """;

    private UserApiExamples() {}
}
