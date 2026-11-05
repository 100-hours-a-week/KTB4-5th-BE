package com.dameokja.backend.notification.infrastructure;

import com.dameokja.backend.notification.domain.MembershipNotificationProgress;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipNotificationProgressRepository extends JpaRepository<MembershipNotificationProgress, Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from MembershipNotificationProgress p where p.refrigerator.id = :id")
    Optional<MembershipNotificationProgress> findByRefrigeratorIdForUpdate(@Param("id") Long refrigeratorId);
}
