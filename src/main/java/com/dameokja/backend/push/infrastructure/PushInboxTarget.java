package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.push.domain.UserDevice;
import com.dameokja.backend.user.domain.User;

public record PushInboxTarget(Notification notification, User recipient, UserDevice device) {
}
