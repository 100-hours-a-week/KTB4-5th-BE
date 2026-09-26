package com.dameokja.backend.notification.application;

import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationRecipient;
import com.dameokja.backend.notification.infrastructure.NotificationRecipientRepository;
import com.dameokja.backend.user.domain.User;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class NotificationRecipientService {
    private final NotificationRecipientRepository notificationRecipientRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public void saveRecipients(Notification notification, List<User> users) {
        List<NotificationRecipient> recipients = users.stream()
                .map(user -> new NotificationRecipient(notification, user))
                .toList();
        notificationRecipientRepository.saveAll(recipients);
    }
}
