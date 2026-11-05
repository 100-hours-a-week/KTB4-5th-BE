package com.dameokja.backend.refrigerator.infrastructure;

import com.dameokja.backend.refrigerator.domain.MembershipTransmission;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MembershipTransmissionRepository extends JpaRepository<MembershipTransmission, Long> {
    Optional<MembershipTransmission> findByRefrigeratorId(Long refrigeratorId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from MembershipTransmission t where t.refrigerator.id = :id")
    Optional<MembershipTransmission> findByRefrigeratorIdForUpdate(@Param("id") Long refrigeratorId);
}
