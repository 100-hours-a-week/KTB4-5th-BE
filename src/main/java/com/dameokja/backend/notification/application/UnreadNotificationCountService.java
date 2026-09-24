package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.infrastructure.NotificationRecipientRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UnreadNotificationCountService {
    private final RefrigeratorAccessService refrigeratorAccessService;
    private final NotificationRecipientRepository recipientRepository;

    public long getUnreadCount(Long userId, Long refrigeratorId) {
        refrigeratorAccessService.validateReadAccess(userId, refrigeratorId);
        return recipientRepository.countUnread(userId, refrigeratorId);
    }
}
