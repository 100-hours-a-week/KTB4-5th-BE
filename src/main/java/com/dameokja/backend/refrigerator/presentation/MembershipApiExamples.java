package com.dameokja.backend.refrigerator.presentation;

final class MembershipApiExamples {
    static final String JOIN_REQUEST = """
            {"inviteCode": "P2F32Y"}
            """;
    static final String JOINED_RESPONSE = """
            {
              "code": "REFRIGERATOR-201-001",
              "message": "냉장고 참여 성공.",
              "data": {"refrigeratorId": "2", "activeRefrigeratorIds": ["2"]}
            }
            """;
    static final String MEMBERS_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-003",
              "message": "냉장고 공유 참여자 목록 조회 성공",
              "data": {
                "refrigeratorName": "우리 냉장고",
                "memberNum": 2,
                "memberMax": 150,
                "members": [
                  {"userId": "1", "nickname": "소유자", "profileImage": "profiles/default.png", "role": "owner"},
                  {"userId": "2", "nickname": "구성원", "profileImage": "profiles/default.png", "role": "member"}
                ]
              }
            }
            """;
    static final String STATS_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-004",
              "message": "냉장고 참여 인원 조회 성공",
              "data": {"memberNum": 2}
            }
            """;
    static final String LEFT_RESPONSE = """
            {
              "code": "REFRIGERATOR-200-005",
              "message": "냉장고 공유 나가기 성공.",
              "data": {"activeRefrigeratorIds": ["1"]}
            }
            """;

    private MembershipApiExamples() {
    }
}
