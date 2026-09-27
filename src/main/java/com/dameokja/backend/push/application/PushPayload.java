package com.dameokja.backend.push.application;

import com.dameokja.backend.notification.domain.Notification;

// 서비스 워커가 알림을 띄우고 눌렀을 때 해당 냉장고의 알림으로 이동하는 데 쓰는 값이다.
// type은 알림 API 응답과 같은 값(apiType)을 쓴다.
record PushPayload(Long notificationId, Long refrigeratorId, String type, String title, String body) {

    static PushPayload from(Notification notification) {
        return new PushPayload(notification.getId(), notification.getRefrigerator().getId(),
                notification.getType().apiType(), notification.getTitle(), notification.getBody());
    }
}
