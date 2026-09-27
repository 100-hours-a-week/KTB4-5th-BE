package com.dameokja.backend.push.domain;

// last_error_code에 저장하는 실패·취소 원인이다. 스키마 규칙에 따라 URL이나 비밀값 원문은 담지 않는다.
public enum PushNotificationErrorCode {
    // 푸시 서비스 전송이 실패했다. 재시도 대상이다.
    SEND_FAILED,
    // 발송 기한(expires_at)이 지나 더 보내지 않는다.
    SEND_WINDOW_EXPIRED,
    // 구독이 해제되었거나 만료되어 보낼 수 없다.
    SUBSCRIPTION_UNAVAILABLE,
    // 수신자가 알림을 더 받을 수 없다. (탈퇴, 냉장고 접근권한 상실, 수신 설정 끔)
    RECIPIENT_UNAVAILABLE
}
