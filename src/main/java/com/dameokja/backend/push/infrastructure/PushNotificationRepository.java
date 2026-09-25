package com.dameokja.backend.push.infrastructure;

import com.dameokja.backend.push.domain.PushNotification;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
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

    // 전송은 트랜잭션 밖에서 하므로 구독 버전 확인에 쓸 구독을 함께 읽는다.
    @Query("""
            select job
            from PushNotification job
                join fetch job.userDevice
            where job.status in (PENDING, RETRY)
                and job.nextAttemptAt <= :now
            """)
    List<PushNotification> findDueJobs(@Param("now") LocalDateTime now);

    @Query("select min(job.nextAttemptAt) from PushNotification job where job.status in (PENDING, RETRY)")
    Optional<LocalDateTime> findNextAttemptAt();

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
