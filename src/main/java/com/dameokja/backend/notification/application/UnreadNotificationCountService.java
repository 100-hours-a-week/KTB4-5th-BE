package com.dameokja.backend.notification.application;

import org.springframework.stereotype.Service;

// 미읽음 수만 필요한 클래스가 알림 목록·읽음 처리까지 담당하는 서비스에 의존하지 않도록 분리한다.
@Service
public class UnreadNotificationCountService {

    public long getUnreadCount(Long userId, Long refrigeratorId) {
        // 1. 냉장고 접근 서비스를 통해 요청한 활성 냉장고의 조회 권한을 확인한다.
        // 2. 알림 DB와 알림 수신자 DB를 조인해 현재 사용자·냉장고로 범위를 제한한다.
        // 3. 읽은 시각이 없는 수신 기록의 개수를 조회한다.
        // 4. 조회한 개수를 반환한다. 해당 기록이 없으면 집계 결과는 0이다.
        throw new UnsupportedOperationException("미읽음 알림 수 조회 흐름만 작성된 상태입니다.");
    }
}
