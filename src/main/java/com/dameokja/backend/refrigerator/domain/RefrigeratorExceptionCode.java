package com.dameokja.backend.refrigerator.domain;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum RefrigeratorExceptionCode implements ExceptionCode {
    OWN_REFRIGERATOR(HttpStatus.BAD_REQUEST, "REFRIGERATOR-400-001", "본인의 냉장고에는 참여할 수 없습니다."),
    INVALID_INVITE(HttpStatus.BAD_REQUEST, "REFRIGERATOR-400-002", "초대코드 형식이 잘못됐습니다."),
    FULL(HttpStatus.BAD_REQUEST, "REFRIGERATOR-400-003", "냉장고 공유 정원이 가득 차 참여할 수 없습니다."),
    EXPIRED_INVITE(HttpStatus.BAD_REQUEST, "REFRIGERATOR-400-004", "초대코드가 만료됐습니다."),
    OWNER_CANNOT_LEAVE(HttpStatus.BAD_REQUEST, "REFRIGERATOR-400-005", "본인이 소유한 냉장고에서는 나갈 수 없습니다."),
    OWNER_ONLY(HttpStatus.FORBIDDEN, "REFRIGERATOR-403-002", "냉장고 소유자만 다른 공유 참여자를 내보낼 수 있습니다."),
    INVITE_NOT_FOUND(HttpStatus.NOT_FOUND, "REFRIGERATOR-404-002", "존재하지 않는 초대코드입니다."),
    MEMBER_NOT_FOUND(HttpStatus.NOT_FOUND, "REFRIGERATOR-404-003", "냉장고 또는 해당 냉장고의 공유 참여자가 존재하지 않습니다."),
    ALREADY_SHARING(HttpStatus.CONFLICT, "REFRIGERATOR-409-001", "이미 다른 냉장고에 참여 중입니다."),
    ACCESS_DENIED(HttpStatus.FORBIDDEN, "REFRIGERATOR-403-001", "해당 냉장고에 접근할 수 없습니다."),
    REFRIGERATOR_NOT_FOUND(HttpStatus.NOT_FOUND, "REFRIGERATOR-404-001", "냉장고를 찾을 수 없습니다."),
    REFRIGERATOR_DELETED(HttpStatus.GONE, "REFRIGERATOR-410-001", "삭제된 냉장고입니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
