package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.PushNotification;
import com.dameokja.backend.push.domain.UserDevice;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PushNotificationRepository extends JpaRepository<PushNotification, Long> {
    // 기간 안에 생성된 알림의 수신자 중 해당 알림 수신을 켠 회원의 활성 구독을 (알림, 구독) 쌍으로 찾는다.
    // v1은 만료·임박 알림만 푸시한다. 푸시할 알림 종류가 늘면 타입 조건에 추가한다.
    // 회원마다 설정 행이 종류별로 있으므로 설정 종류(p.type)를 함께 걸어야 끈 알림이 가지 않고 대상이 중복되지 않는다.
    // 현재 구독 버전으로 이미 만든 작업은 제외하므로 다시 실행해도 작업이 중복되지 않는다.
    // select의 n, d는 Spring Data JPA가 반환 타입인 PushInboxTarget(notification, device) 생성자에 순서대로 넣는다.
    @Query("""
            select n, d
            from NotificationRecipient r
                join r.notification n
                join UserDevice d on d.user = r.user
                join NotificationPreference p on p.user = r.user
            where n.type in (EXPIRED, EXPIRING)
                and n.createdAt >= :from and n.createdAt < :to
                and d.status = ACTIVE
                and p.type = EXPIRATION and p.isEnabled = true
                and not exists (
                    select 1 from PushNotification job
                    where job.notification = n
                        and job.userDevice = d
                        and job.subscriptionVersion = d.subscriptionVersion)
            """)
    List<PushInboxTarget> findInboxPushTargets(@Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Query("""
            select job
            from PushNotification job
                join fetch job.userDevice
            where job.status in (PENDING, RETRY)
                and job.nextAttemptAt <= :now
            """)
    List<PushNotification> findDueJobs(@Param("now") LocalDateTime now);

    @Query(value = """
            SELECT push_notification_id
            FROM push_notifications
            WHERE (status IN ('PENDING', 'RETRY') AND next_attempt_at <= :now)
                OR (status = 'PROCESSING' AND lease_until <= :now)
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Long> findLeaseableJobIdsWithSkipLock(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Modifying
    @Query(value = """
            UPDATE push_notifications
            SET status = 'PROCESSING',
                lease_token = :leaseToken,
                lease_until = :endAt,
                updated_at = :startAt
            WHERE push_notification_id IN (:ids)
            """, nativeQuery = true)
    int leasePushJobs(@Param("ids") List<Long> ids, @Param("leaseToken") String leaseToken,
            @Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Query("""
            select job
            from PushNotification job
                join fetch job.userDevice device
                join fetch device.user
                join fetch job.user
                left join fetch job.refrigerator
            where job.id = :jobId
                and job.status = PROCESSING
                and job.leaseToken = :leaseToken
                and job.leaseUntil > :now
            """)
    Optional<PushNotification> findLeasedPushJob(@Param("jobId") Long jobId,
                                                 @Param("leaseToken") String leaseToken,
                                                 @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE push_notifications
            SET status = 'FAILED',
                last_error_code = 'SEND_WINDOW_EXPIRED',
                next_attempt_at = NULL,
                lease_token = NULL,
                lease_until = NULL,
                updated_at = :now
            WHERE push_notification_id = :jobId
                AND status = 'PROCESSING'
                AND lease_token = :leaseToken
                AND lease_until > :now
                AND expires_at <= :now
            """, nativeQuery = true)
    int expireLeasedPushJob(@Param("jobId") Long jobId, @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE push_notifications
            SET status = 'CANCELLED',
                last_error_code = :reason,
                next_attempt_at = NULL,
                lease_token = NULL,
                lease_until = NULL,
                updated_at = :now
            WHERE push_notification_id = :jobId
                AND status = 'PROCESSING'
                AND lease_token = :leaseToken
                AND lease_until > :now
            """, nativeQuery = true)
    int cancelLeasedPushJob(@Param("jobId") Long jobId, @Param("leaseToken") String leaseToken,
            @Param("reason") String reason, @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE push_notifications
            SET status = 'ACCEPTED',
                attempt_count = attempt_count + 1,
                accepted_at = :now,
                next_attempt_at = NULL,
                lease_token = NULL,
                lease_until = NULL,
                updated_at = :now
            WHERE push_notification_id = :jobId
                AND status = 'PROCESSING'
                AND lease_token = :leaseToken
                AND lease_until > :now
            """, nativeQuery = true)
    int acceptLeasedPushJob(@Param("jobId") Long jobId, @Param("leaseToken") String leaseToken,
            @Param("now") LocalDateTime now);

    @Modifying
    @Query(value = """
            UPDATE push_notifications
            SET status = :status,
                attempt_count = :attemptCount,
                next_attempt_at = :nextAttemptAt,
                last_error_code = 'SEND_FAILED',
                lease_token = NULL,
                lease_until = NULL,
                updated_at = :now
            WHERE push_notification_id = :jobId
                AND status = 'PROCESSING'
                AND lease_token = :leaseToken
                AND lease_until > :now
            """, nativeQuery = true)
    int failLeasedPushJob(@Param("jobId") Long jobId, @Param("leaseToken") String leaseToken,
            @Param("status") String status, @Param("attemptCount") int attemptCount,
            @Param("nextAttemptAt") LocalDateTime nextAttemptAt, @Param("now") LocalDateTime now);

    @Query("select min(job.nextAttemptAt) from PushNotification job where job.status in (PENDING, RETRY)")
    Optional<LocalDateTime> findNextAttemptAt();

    boolean existsByDispatchKey(String dispatchKey);

    @Query("select d from UserDevice d join fetch d.user u where u.id = :userId and u.status = ACTIVE and d.status = ACTIVE")
    List<UserDevice> findActiveAnalysisDevices(@Param("userId") Long userId);

    // 대기 중 변경된 소유자·구독 버전·활성 상태와 독립적인 분석 수신 설정을 실제 전송 직전에 확인한다.
    @Query("""
            select count(job) > 0 from PushNotification job
                join job.userDevice d join NotificationPreference p on p.user = job.user
            where job.id = :jobId and job.kind = ANALYSIS
                and job.user.status = ACTIVE and p.type = ANALYSIS and p.isEnabled = true
                and d.status = ACTIVE and d.user = job.user and d.subscriptionVersion = job.subscriptionVersion
            """)
    boolean canReceiveAnalysisPush(@Param("jobId") Long jobId);

    // 작업 생성 뒤 탈퇴, 냉장고 탈퇴·삭제, 수신 끔이 있었을 수 있으므로 발송 직전에 다시 확인한다.
    @Query("""
            select count(m) > 0
            from RefrigeratorMember m
                join NotificationPreference p on p.user = m.user
            where m.user.id = :userId and m.refrigerator.id = :refrigeratorId
                and m.user.status = ACTIVE
                and m.refrigerator.deletedAt is null
                and p.type = EXPIRATION and p.isEnabled = true
            """)
    boolean canReceiveExpirationPush(@Param("userId") Long userId, @Param("refrigeratorId") Long refrigeratorId);
}
