package com.dameokja.backend.refrigerator.presentation.response;

import com.dameokja.backend.global.response.SuccessCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum RefrigeratorSuccessCode implements SuccessCode {
    MYPAGE("REFRIGERATOR-200-007", "마이페이지 조회 성공"),
    INVITATION_ISSUED("REFRIGERATOR-200-002", "초대코드 발급 성공"),
    INVITATION_RETRIEVED("REFRIGERATOR-200-006", "초대코드 조회 성공"),
    MEMBERS_LIST("REFRIGERATOR-200-003", "냉장고 공유 참여자 목록 조회 성공"),
    MEMBER_STATS("REFRIGERATOR-200-004", "냉장고 참여 인원 조회 성공"),
    JOINED("REFRIGERATOR-201-001", "냉장고 참여 성공."),
    LEFT("REFRIGERATOR-200-005", "냉장고 공유 나가기 성공.");

    private final String code;
    private final String message;
}
