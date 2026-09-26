package com.dameokja.backend.notification.domain;

import com.dameokja.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "notification_recipients")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationRecipient {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "notification_recipient_id")
    private Long id;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "notification_id", nullable = false, updatable = false)
    private Notification notification;

    public NotificationRecipient(Notification notification, User user) {
        this.notification = Objects.requireNonNull(notification, "수신 대상 알림은 필수입니다.");
        this.user = Objects.requireNonNull(user, "알림 수신자는 필수입니다.");
    }

    public void markRead(LocalDateTime readAt) {
        Objects.requireNonNull(readAt, "알림을 읽은 시각은 필수입니다.");
        if (this.readAt != null) {
            return;
        }
        this.readAt = readAt;
    }
}
