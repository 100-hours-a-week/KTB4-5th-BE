package com.dameokja.backend.notification.application;

import com.dameokja.backend.global.common.EventEnvelope;
import com.dameokja.backend.notification.domain.MembershipNotificationProgress;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.notification.domain.NotificationType;
import com.dameokja.backend.notification.infrastructure.MembershipNotificationProgressRepository;
import com.dameokja.backend.notification.infrastructure.NotificationRepository;
import com.dameokja.backend.refrigerator.application.RefrigeratorAccessService;
import com.dameokja.backend.refrigerator.application.membership.MembershipHistoryService;
import com.dameokja.backend.refrigerator.domain.MembershipChangeEvent;
import com.dameokja.backend.user.application.access.UserAccessService;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MembershipNotificationProcessor {
    private final MembershipHistoryService history;
    private final MembershipNotificationProgressRepository progress;
    private final NotificationRepository notifications;
    private final NotificationRecipientService recipients;
    private final RefrigeratorAccessService refrigerators;
    private final UserAccessService users;

    /**
     * 원본과 순번을 검증하고 알림·수신자·완료 위치를 원자적으로 저장한다.
     * @param key Kafka가 전달한 냉장고 ID 문자열
     * @param envelope 공통 외곽과 불변 구성원 사건
     */
    @Transactional
    public void process(String key, EventEnvelope<MembershipChangeEvent> envelope) {
        MembershipChangeEvent event = validateOriginal(key, envelope);
        MembershipNotificationProgress completed = progress.findByRefrigeratorIdForUpdate(event.refrigeratorId())
                .orElseGet(() -> progress.saveAndFlush(
                        new MembershipNotificationProgress(refrigerators.getReferenceById(event.refrigeratorId()))));
        if (completed.alreadyCompleted(event.sequence())) {
            return;
        }
        completed.complete(event.sequence());
        if (!event.recipientUserIds().isEmpty()) {
            saveNotification(event);
        }
    }

    private MembershipChangeEvent validateOriginal(String key, EventEnvelope<MembershipChangeEvent> envelope) {
        MembershipChangeEvent event = envelope.payload();
        if (!Objects.equals(key, event.refrigeratorId().toString()) || !event.messageId().equals(envelope.messageId())
                || !event.equals(history.original(event.refrigeratorId(), event.sequence()))) {
            throw new IllegalArgumentException("구성원 메시지가 참여 업무 원본과 일치하지 않습니다.");
        }
        return event;
    }

    private void saveNotification(MembershipChangeEvent event) {
        NotificationType type = switch (event.type()) {
            case JOINED -> NotificationType.MEMBER_JOINED;
            case LEFT -> NotificationType.MEMBER_LEFT;
            case KICKED -> NotificationType.MEMBER_KICKED;
        };
        Notification notification = notifications.saveAndFlush(new Notification(type, event.title(), event.body(),
                refrigerators.getReferenceById(event.refrigeratorId())));
        recipients.saveRecipients(notification, event.recipientUserIds().stream().map(users::getReferenceById).toList());
        // JPA 감사 시각은 처리 시각이므로, 지연 복구한 알림도 원래 발생 시각으로 표시한다.
        notifications.preserveOccurredAt(notification.getId(), event.occurredAt());
    }
}
