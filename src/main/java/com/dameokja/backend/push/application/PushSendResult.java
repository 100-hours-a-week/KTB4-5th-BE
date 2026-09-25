package com.dameokja.backend.push.application;

// 발송 작업이 재시도할지(FAILED), 발송을 취소할지(UNAVAILABLE) 구분할 수 있도록 결과를 나눈다.
public enum PushSendResult {
    // 푸시 서비스가 메시지를 수락했다. 기기 도달이나 읽음은 뜻하지 않는다.
    ACCEPTED,
    // 구독이 비활성이거나 만료되어 이 구독으로는 더 보낼 수 없다.
    UNAVAILABLE,
    // 일시적인 오류일 수 있어 다시 보낼 수 있다.
    FAILED
}
