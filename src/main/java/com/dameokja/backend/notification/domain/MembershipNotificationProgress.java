package com.dameokja.backend.notification.domain;

import com.dameokja.backend.global.common.BaseEntity;
import com.dameokja.backend.refrigerator.domain.Refrigerator;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "membership_notification_progress")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MembershipNotificationProgress extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "membership_notification_progress_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "refrigerator_id", nullable = false, unique = true, updatable = false)
    private Refrigerator refrigerator;
    @Column(nullable = false)
    private long completedSequence;

    public MembershipNotificationProgress(Refrigerator refrigerator) {
        this.refrigerator = refrigerator;
    }

    public boolean alreadyCompleted(long sequence) {
        return sequence <= completedSequence;
    }

    public void complete(long sequence) {
        if (sequence != completedSequence + 1) {
            throw new IllegalStateException("선행 구성원 알림이 완료되지 않았습니다.");
        }
        completedSequence = sequence;
    }
}
