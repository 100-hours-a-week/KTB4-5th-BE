package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.push.domain.UserDevice;

// 구독은 한 회원에게만 속하므로 수신자는 device.getUser()로 얻는다.
public record PushInboxTarget(Notification notification, UserDevice device) {
}
