package com.dameokja.backend.push.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.notification.domain.Notification;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import com.dameokja.backend.user.domain.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

// 구독(기기) 하나에 보낼 푸시 한 건의 발송 작업이다. 재시도는 새 행을 만들지 않고 이 행의 상태를 바꾼다.
// 단일 인스턴스 전제라 선점용 lease_until·claim_token 컬럼은 매핑하지 않는다.
@Getter
@Entity
@Table(name = "push_notifications")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class PushNotification extends BaseEntity {
    // 첫 전송 실패 뒤 5분, 두 번째 뒤 15분, 세 번째 뒤 30분 후에 다시 보내고, 네 번째 실패하면 끝낸다.
    private static final List<Duration> RETRY_DELAYS =
            List.of(Duration.ofMinutes(5), Duration.ofMinutes(15), Duration.ofMinutes(30));
    private static final String INBOX_DISPATCH_KEY_PREFIX = "INBOX:";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "push_notification_id")
    private Long id;

    @Column(name = "dispatch_key", nullable = false, length = 191, updatable = false)
    private String dispatchKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 20, updatable = false)
    private PushNotificationKind kind;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_device_id", nullable = false, updatable = false)
    private UserDevice userDevice;

    // 작업 생성 뒤 구독이 해제·갱신되면 기기의 버전이 올라가므로, 발송 전에 이 값과 비교해 옛 구독으로 보내지 않는다.
    @Column(name = "subscription_version", nullable = false, updatable = false)
    private Long subscriptionVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private PushNotificationStatus status;

    @Column(name = "attempt_count", nullable = false)
    private Integer attemptCount;

    // 이 시각부터 보낼 수 있다. 첫 발송은 생성 시각, 재시도는 다음 발송 시각이다.
    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "last_error_code", length = 64)
    private PushNotificationErrorCode lastErrorCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", updatable = false)
    private Notification notification;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refrigerator_id", nullable = false, updatable = false)
    private Refrigerator refrigerator;

    public static PushNotification inbox(Notification notification, User recipient, UserDevice device,
            String payload, LocalDateTime now, LocalDateTime expiresAt) {
        PushNotification job = new PushNotification();
        job.dispatchKey = INBOX_DISPATCH_KEY_PREFIX + notification.getId();
        job.kind = PushNotificationKind.INBOX;
        job.userDevice = device;
        job.subscriptionVersion = device.getSubscriptionVersion();
        job.payload = payload;
        job.status = PushNotificationStatus.PENDING;
        job.attemptCount = 0;
        job.nextAttemptAt = now;
        job.expiresAt = expiresAt;
        job.notification = notification;
        job.user = recipient;
        job.refrigerator = notification.getRefrigerator();
        return job;
    }

    public void accept(LocalDateTime acceptedAt) {
        this.attemptCount = this.attemptCount + 1;
        this.status = PushNotificationStatus.ACCEPTED;
        this.acceptedAt = acceptedAt;
        this.nextAttemptAt = null;
    }

    public void fail(LocalDateTime failedAt) {
        this.attemptCount = this.attemptCount + 1;
        this.lastErrorCode = PushNotificationErrorCode.SEND_FAILED;
        if (this.attemptCount > RETRY_DELAYS.size()) {
            finish(PushNotificationStatus.FAILED);
            return;
        }
        LocalDateTime retryAt = failedAt.plus(RETRY_DELAYS.get(this.attemptCount - 1));
        if (isExpired(retryAt)) {
            finish(PushNotificationStatus.FAILED);
            return;
        }
        this.status = PushNotificationStatus.RETRY;
        this.nextAttemptAt = retryAt;
    }

    // 취소는 전송 전에 수신 자격을 다시 확인하다 결정되기도 하므로 시도 횟수를 세지 않는다.
    public void cancel(PushNotificationErrorCode reason) {
        this.lastErrorCode = reason;
        finish(PushNotificationStatus.CANCELLED);
    }

    public void expire() {
        this.lastErrorCode = PushNotificationErrorCode.SEND_WINDOW_EXPIRED;
        finish(PushNotificationStatus.FAILED);
    }

    public boolean isExpired(LocalDateTime now) {
        return !now.isBefore(this.expiresAt);
    }

    private void finish(PushNotificationStatus finalStatus) {
        this.status = finalStatus;
        this.nextAttemptAt = null;
    }
}
