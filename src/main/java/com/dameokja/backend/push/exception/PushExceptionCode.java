package com.dameokja.backend.push.exception;

import com.dameokja.backend.global.exception.ExceptionCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum PushExceptionCode implements ExceptionCode {
    SUBSCRIPTION_OWNER_CONFLICT(HttpStatus.CONFLICT, "PUSH-409-001",
            "다른 계정에서 이미 사용 중인 구독입니다."),
    SUBSCRIPTION_FORBIDDEN(HttpStatus.FORBIDDEN, "PUSH-403-001", "본인 구독만 해제할 수 있습니다."),
    SUBSCRIPTION_NOT_FOUND(HttpStatus.NOT_FOUND, "PUSH-404-001", "구독을 찾을 수 없습니다."),
    LEASE_DURATION_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH-500-001", "lease 기간은 양수여야 합니다."),
    LEASE_UPDATE_COUNT_MISMATCH(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH-500-002",
            "조회한 작업 수와 lease 기록 건수가 일치하지 않습니다."),
    WORKER_SLOT_LIMIT_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH-500-003", "워커 최대 자리 수는 양수여야 합니다."),
    WORKER_SLOT_OCCUPANCY_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH-500-004",
            "점유할 작업 수가 사용 가능한 자리 범위를 벗어났습니다."),
    WORKER_SLOT_RELEASE_INVALID(HttpStatus.INTERNAL_SERVER_ERROR, "PUSH-500-005", "사용 중인 자리가 없어 반납할 수 없습니다.");

    private final HttpStatus status;
    private final String code;
    private final String message;
}
